-- Demo data: 4 teams, 11 users covering every role, ~5,000 work items with history.
-- Lives in db/seed so automated tests (which load only db/migration) start empty.
-- All demo passwords are "demo1234" (bcrypt via pgcrypto, compatible with Spring's BCryptPasswordEncoder).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (email, display_name, password_hash, is_admin)
SELECT email, display_name, crypt('demo1234', gen_salt('bf', 10)), is_admin
FROM (VALUES
    ('admin@newtonite.test', 'Ada Admin',      TRUE),
    ('alice@newtonite.test', 'Alice Sharma',   FALSE),
    ('bob@newtonite.test',   'Bob Mehta',      FALSE),
    ('judy@newtonite.test',  'Judy Iyer',      FALSE),
    ('carol@newtonite.test', 'Carol Singh',    FALSE),
    ('dave@newtonite.test',  'Dave Rao',       FALSE),
    ('frank@newtonite.test', 'Frank Das',      FALSE),
    ('ivan@newtonite.test',  'Ivan Bose',      FALSE),
    ('erin@newtonite.test',  'Erin Nair',      FALSE),
    ('grace@newtonite.test', 'Grace Kapoor',   FALSE),
    ('heidi@newtonite.test', 'Heidi Verma',    FALSE)
) AS u(email, display_name, is_admin);

INSERT INTO teams (name) VALUES ('Payments'), ('Platform Engineering'), ('Customer Support'), ('Compliance');

INSERT INTO memberships (user_id, team_id, role)
SELECT u.id, t.id, m.role
FROM (VALUES
    ('alice@newtonite.test', 'Payments',             'LEAD'),
    ('alice@newtonite.test', 'Compliance',           'MEMBER'),
    ('bob@newtonite.test',   'Payments',             'MEMBER'),
    ('judy@newtonite.test',  'Payments',             'MEMBER'),
    ('dave@newtonite.test',  'Payments',             'VIEWER'),
    ('carol@newtonite.test', 'Platform Engineering', 'LEAD'),
    ('dave@newtonite.test',  'Platform Engineering', 'MEMBER'),
    ('frank@newtonite.test', 'Platform Engineering', 'MEMBER'),
    ('ivan@newtonite.test',  'Platform Engineering', 'VIEWER'),
    ('erin@newtonite.test',  'Customer Support',     'LEAD'),
    ('frank@newtonite.test', 'Customer Support',     'MEMBER'),
    ('heidi@newtonite.test', 'Customer Support',     'MEMBER'),
    ('grace@newtonite.test', 'Compliance',           'LEAD'),
    ('heidi@newtonite.test', 'Compliance',           'MEMBER')
) AS m(email, team, role)
JOIN users u ON u.email = m.email
JOIN teams t ON t.name = m.team;

-- Deterministic pseudo-random data.
SELECT setseed(0.42);

CREATE TEMP TABLE seed_items AS
WITH team_info AS (
    SELECT t.id AS team_id,
           t.name AS team_name,
           (SELECT array_agg(m.user_id ORDER BY m.user_id)
              FROM memberships m
             WHERE m.team_id = t.id AND m.role IN ('MEMBER', 'LEAD')) AS workers,
           row_number() OVER (ORDER BY t.id) AS idx
    FROM teams t
),
draws AS (
    SELECT g, random() AS r_team, random() AS r_type, random() AS r_pri, random() AS r_status,
           random() AS r_user, random() AS r_creator, random() AS r_age, random() AS r_upd,
           random() AS r_due, random() AS r_title
    FROM generate_series(1, 5000) AS g
),
typed AS (
    SELECT d.*, ti.team_id, ti.workers,
           CASE ti.team_name
               WHEN 'Payments'             THEN CASE WHEN d.r_type < 0.7 THEN 'PAYMENT' ELSE 'TASK' END
               WHEN 'Compliance'           THEN CASE WHEN d.r_type < 0.7 THEN 'COMPLIANCE' ELSE 'TASK' END
               WHEN 'Platform Engineering' THEN CASE WHEN d.r_type < 0.5 THEN 'INCIDENT'
                                                     WHEN d.r_type < 0.9 THEN 'ENGINEERING' ELSE 'TASK' END
               ELSE CASE WHEN d.r_type < 0.75 THEN 'CUSTOMER_ISSUE' ELSE 'TASK' END
           END AS type
    FROM draws d
    JOIN team_info ti ON ti.idx = 1 + floor(d.r_team * 4)::int
),
statused AS (
    SELECT t.*,
           CASE WHEN r_pri < 0.30 THEN 'LOW' WHEN r_pri < 0.65 THEN 'MEDIUM'
                WHEN r_pri < 0.90 THEN 'HIGH' ELSE 'URGENT' END AS priority,
           CASE WHEN r_status < 0.22 THEN 'OPEN'
                WHEN r_status < 0.45 THEN 'IN_PROGRESS'
                WHEN r_status < 0.55 THEN CASE WHEN type IN ('PAYMENT', 'COMPLIANCE')
                                               THEN 'AWAITING_APPROVAL' ELSE 'IN_PROGRESS' END
                WHEN r_status < 0.72 THEN 'RESOLVED'
                ELSE 'CLOSED' END AS status,
           now() - (r_age * interval '180 days') AS created_at
    FROM typed t
)
SELECT g, team_id, type, priority, status,
       CASE WHEN status = 'OPEN' AND r_user < 0.6 THEN NULL
            ELSE workers[1 + floor(r_user * array_length(workers, 1))::int] END AS assignee_id,
       workers[1 + floor(r_creator * array_length(workers, 1))::int] AS created_by,
       CASE WHEN r_due < 0.5 THEN created_at + (3 + r_due * 40) * interval '1 day' END AS due_at,
       created_at,
       created_at + (now() - created_at) * r_upd AS updated_at,
       (CASE type
            WHEN 'PAYMENT'        THEN (ARRAY['Refund request', 'Chargeback investigation', 'Failed payout', 'Duplicate charge', 'Settlement mismatch'])[1 + floor(r_title * 5)::int]
            WHEN 'COMPLIANCE'     THEN (ARRAY['KYC document review', 'Sanctions screening hit', 'Data deletion request', 'Audit evidence request', 'Policy exception'])[1 + floor(r_title * 5)::int]
            WHEN 'INCIDENT'       THEN (ARRAY['API latency spike', 'Checkout errors elevated', 'Database CPU alert', 'Queue backlog growing', 'Certificate expiring'])[1 + floor(r_title * 5)::int]
            WHEN 'ENGINEERING'    THEN (ARRAY['Flaky deployment pipeline', 'Upgrade library version', 'Memory leak in worker', 'Slow report query', 'Retry storm in client'])[1 + floor(r_title * 5)::int]
            WHEN 'CUSTOMER_ISSUE' THEN (ARRAY['Customer cannot log in', 'Order stuck in processing', 'Invoice shows wrong amount', 'Account locked', 'Missing order confirmation'])[1 + floor(r_title * 5)::int]
            ELSE (ARRAY['Update runbook', 'Prepare weekly report', 'Rotate on-call schedule', 'Clean up stale tickets', 'Review access list'])[1 + floor(r_title * 5)::int]
        END) || ' - ref ' || (10000 + g) AS title
FROM statused;

INSERT INTO work_items (team_id, title, description, type, priority, status, assignee_id, created_by, due_at, created_at, updated_at, version)
SELECT team_id, title,
       'Seeded demo item. Reference ' || (10000 + g) || '. Investigate, take action and record the outcome.',
       type, priority, status, assignee_id, created_by, due_at, created_at, updated_at,
       CASE WHEN status = 'OPEN' THEN 0 ELSE 2 END
FROM seed_items
ORDER BY g;

-- A few hand-written items for the demo, most recently updated so they appear first.
INSERT INTO work_items (team_id, title, description, type, priority, status, assignee_id, created_by, due_at)
SELECT t.id, v.title, v.description, v.type, v.priority, v.status, a.id, c.id, now() + v.due
FROM (VALUES
    ('Platform Engineering', 'Production checkout latency spike',
     'p95 latency on /checkout is above 3s since 09:40. Needs an owner now.',
     'INCIDENT', 'URGENT', 'OPEN', NULL, 'carol@newtonite.test', interval '4 hours'),
    ('Payments', 'Refund 12,400 INR to merchant M-2231',
     'Merchant was double-charged on 14 Oct. Refund prepared, needs Lead approval before release.',
     'PAYMENT', 'HIGH', 'AWAITING_APPROVAL', 'bob@newtonite.test', 'bob@newtonite.test', interval '1 day'),
    ('Compliance', 'Quarterly KYC audit evidence',
     'Collect evidence for the Q3 KYC audit sample of 40 accounts.',
     'COMPLIANCE', 'MEDIUM', 'IN_PROGRESS', 'heidi@newtonite.test', 'grace@newtonite.test', interval '5 days'),
    ('Customer Support', 'Enterprise customer cannot export invoices',
     'Acme Corp reports CSV export of invoices fails with a timeout.',
     'CUSTOMER_ISSUE', 'HIGH', 'OPEN', NULL, 'erin@newtonite.test', interval '2 days')
) AS v(team, title, description, type, priority, status, assignee, creator, due)
JOIN teams t ON t.name = v.team
JOIN users c ON c.email = v.creator
LEFT JOIN users a ON a.email = v.assignee;

-- History for every item: created, assigned (if any), status change (if not OPEN).
INSERT INTO activity_events (item_id, actor_id, type, data, created_at)
SELECT w.id, w.created_by, 'CREATED',
       jsonb_build_object('title', w.title, 'type', w.type, 'priority', w.priority),
       w.created_at
FROM work_items w;

INSERT INTO activity_events (item_id, actor_id, type, data, created_at)
SELECT w.id, w.assignee_id, 'ASSIGNED',
       jsonb_build_object('fromUserId', NULL, 'fromName', NULL,
                          'toUserId', w.assignee_id, 'toName', u.display_name, 'via', 'CLAIM'),
       w.created_at + (w.updated_at - w.created_at) / 3
FROM work_items w
JOIN users u ON u.id = w.assignee_id;

INSERT INTO activity_events (item_id, actor_id, type, data, created_at)
SELECT w.id, w.assignee_id, 'STATUS_CHANGED',
       jsonb_build_object('from', 'OPEN', 'to', w.status),
       w.updated_at
FROM work_items w
WHERE w.status <> 'OPEN';

-- Some discussion on every 7th item.
INSERT INTO comments (item_id, author_id, body, created_at)
SELECT w.id, w.created_by, 'Initial findings attached to the runbook. Looking into it.',
       w.created_at + (w.updated_at - w.created_at) / 2
FROM work_items w
WHERE w.id % 7 = 0;

INSERT INTO activity_events (item_id, actor_id, type, data, created_at)
SELECT c.item_id, c.author_id, 'COMMENTED',
       jsonb_build_object('commentId', c.id, 'excerpt', left(c.body, 80)),
       c.created_at
FROM comments c;

DROP TABLE seed_items;
