-- Core schema. Enumerations are VARCHAR + CHECK (easy to extend with a new migration,
-- maps directly to Java enums with @Enumerated(STRING)).

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(200) NOT NULL UNIQUE,
    display_name  VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    is_admin      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE teams (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A user can belong to several teams with a different role in each.
CREATE TABLE memberships (
    user_id BIGINT      NOT NULL REFERENCES users (id),
    team_id BIGINT      NOT NULL REFERENCES teams (id),
    role    VARCHAR(20) NOT NULL CHECK (role IN ('VIEWER', 'MEMBER', 'LEAD')),
    PRIMARY KEY (user_id, team_id)
);
CREATE INDEX idx_memberships_team ON memberships (team_id);

CREATE TABLE work_items (
    id            BIGSERIAL PRIMARY KEY,
    team_id       BIGINT       NOT NULL REFERENCES teams (id),
    title         VARCHAR(200) NOT NULL,
    description   TEXT         NOT NULL DEFAULT '',
    type          VARCHAR(30)  NOT NULL CHECK (type IN ('INCIDENT', 'CUSTOMER_ISSUE', 'ENGINEERING', 'PAYMENT', 'COMPLIANCE', 'TASK')),
    priority      VARCHAR(10)  NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'AWAITING_APPROVAL', 'RESOLVED', 'CLOSED')),
    assignee_id   BIGINT       REFERENCES users (id),
    created_by    BIGINT       NOT NULL REFERENCES users (id),
    due_at        TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,   -- optimistic locking
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Derived columns maintained by Postgres, never written by the application.
    priority_rank SMALLINT GENERATED ALWAYS AS (
        CASE priority WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END
    ) STORED,
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'B')
    ) STORED
);

-- Default list order inside the user's teams (keyset pagination on updated_at, id).
CREATE INDEX idx_items_team_updated ON work_items (team_id, updated_at DESC, id DESC);
-- "Assigned to me".
CREATE INDEX idx_items_assignee_status ON work_items (assignee_id, status, updated_at DESC);
-- "Unassigned urgent": small partial index.
CREATE INDEX idx_items_unassigned_open ON work_items (team_id, priority_rank DESC, updated_at DESC)
    WHERE assignee_id IS NULL AND status = 'OPEN';
-- "Awaiting approval" and status filters.
CREATE INDEX idx_items_team_status ON work_items (team_id, status, updated_at DESC);
-- "Overdue": only unfinished items with a due date.
CREATE INDEX idx_items_due_open ON work_items (team_id, due_at)
    WHERE due_at IS NOT NULL AND status NOT IN ('RESOLVED', 'CLOSED');
-- Full-text search.
CREATE INDEX idx_items_search ON work_items USING GIN (search_vector);

-- Append-only history. Written in the same transaction as the change it describes.
CREATE TABLE activity_events (
    id         BIGSERIAL PRIMARY KEY,
    item_id    BIGINT      NOT NULL REFERENCES work_items (id),
    actor_id   BIGINT      REFERENCES users (id),          -- NULL = system
    type       VARCHAR(30) NOT NULL,
    data       JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- e.g. {"from": "OPEN", "to": "IN_PROGRESS"}
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_activity_item ON activity_events (item_id, created_at DESC, id DESC);

CREATE TABLE comments (
    id         BIGSERIAL PRIMARY KEY,
    item_id    BIGINT      NOT NULL REFERENCES work_items (id),
    author_id  BIGINT      NOT NULL REFERENCES users (id),
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_comments_item ON comments (item_id, created_at DESC, id DESC);

-- Responses of mutating requests, so a retried request is answered without being re-executed.
CREATE TABLE idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    idem_key        VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,   -- SHA-256 of method + path + body
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, idem_key)
);
CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);

-- Transactional outbox: jobs are inserted together with the change that caused them.
CREATE TABLE outbox_jobs (
    id           BIGSERIAL PRIMARY KEY,
    type         VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts     INT          NOT NULL DEFAULT 0,
    max_attempts INT          NOT NULL DEFAULT 5,
    run_after    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON outbox_jobs (run_after, id) WHERE status = 'PENDING';

CREATE TABLE notifications (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id),
    item_id       BIGINT       REFERENCES work_items (id),
    message       VARCHAR(500) NOT NULL,
    source_job_id BIGINT       NOT NULL REFERENCES outbox_jobs (id),
    read_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Makes the job handler idempotent: re-running a job cannot notify the same user twice.
    UNIQUE (source_job_id, user_id)
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
