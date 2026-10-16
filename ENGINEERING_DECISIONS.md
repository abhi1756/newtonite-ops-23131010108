# Engineering Decisions

The six decisions that shape this system, each with the alternatives considered and the
trade-off accepted, followed by what was deliberately left out. Product assumptions are in
[docs/ASSUMPTIONS.md](docs/ASSUMPTIONS.md); the full design is in [docs/DESIGN.md](docs/DESIGN.md).

---

## 1. PostgreSQL is the single source of truth; history and side effects commit with the change

**Context.** The brief's core complaint is that work and decisions "silently disappear". If an
item can change without its history being written (or a notification can be lost, or sent for a
change that was rolled back), the system reproduces the problem it replaces.

**Options considered.**
- Write the change, then write history and enqueue notifications in separate steps (simple, but a
  crash between steps leaves them inconsistent).
- Event sourcing: the event log is the source of truth and items are projections (strong audit
  trail, but a lot of machinery for a one-day build and harder to query).
- **One transaction per action** containing the item change, an append-only `activity_events` row
  and any `outbox_jobs` row.

**Decision.** The third option. `ActivityService.record` and `OutboxService.enqueue` use
`Propagation.MANDATORY`, so they cannot be called outside the caller's transaction. Events store
old and new values as JSONB and snapshot names (e.g. who was assigned), so history stays readable
even if users are renamed. Items are never deleted, only closed.

**Trade-offs.** Every write touches 2–3 tables in one transaction (a little more latency and lock
time per write). History is a log of facts, not a source the state can be rebuilt from. The
`activity_events` table grows without bound; at scale it would be partitioned by time (see README).

**Where.** `WorkItemService`, `ActivityService`, `OutboxService`, `V1__schema.sql`.

---

## 2. Concurrency: atomic conditional claim plus optimistic locking, not pessimistic locks

**Context.** Two people claiming the same item, one person editing while another has just changed
it, and bursts of updates were all called out explicitly.

**Options considered.**
- Last write wins (what the spreadsheets did).
- Pessimistic locking: an "editing" lock held while a user has the form open. This needs lock
  expiry, blocks colleagues and fails badly when someone closes their laptop.
- `SELECT ... FOR UPDATE` per request: correct, but holds row locks across application logic.
- **Optimistic concurrency**, with one special case for claiming.

**Decision.**
- **Claim** is a single conditional statement:
  `UPDATE work_items SET assignee_id = :me, version = version + 1 WHERE id = :id AND assignee_id IS NULL`.
  Postgres re-checks the condition under the row lock, so with ten simultaneous claims exactly one
  updates a row; the others get `409 ALREADY_CLAIMED` naming the winner. Re-claiming your own
  item is a no-op success, so retries are harmless.
- **Edits, assignments and transitions** carry the `version` the user was looking at. A mismatch
  returns `409 STALE_VERSION` with the current item; JPA `@Version` additionally catches a
  concurrent write in the gap between our read and our write.
- **The UI** replaces its cached copy with the server's on 409 and keeps the user's draft. The
  edit form sends only the fields the user changed, so re-applying a draft after a conflict cannot
  silently revert someone else's change to another field.
- Comments do not bump the version, so a discussion never makes someone's pending edit stale.

**Trade-offs.** Users occasionally see "this changed, review and retry" instead of an automatic
merge. Claim deliberately does not require a version: its only precondition is "nobody owns it".

**Where.** `WorkItemRepository.claimIfUnassigned`, `WorkItemService.requireCurrentVersion`,
`useItemMutation` and `EditForm` in the frontend. Tests: `ConcurrencyTest`.

---

## 3. Idempotency keys are stored in the same transaction as the action

**Context.** "A user repeating an action because they are unsure whether the first request
succeeded": double clicks, retries after a timeout, flaky networks.

**Options considered.**
- Disable the button while a request is pending (UI only; does nothing for retries after a timeout).
- Natural de-duplication rules per endpoint (e.g. "same title within 10 seconds"), which are
  guesswork.
- An idempotency cache in Redis, written after the action (a crash between action and cache write
  loses the guarantee).
- **A key row inserted in the action's own transaction.**

**Decision.** Every mutating endpoint accepts an `Idempotency-Key` header; the browser generates
one per user intent and reuses it for automatic retries. The server inserts
`(user_id, key, request_hash)` with `ON CONFLICT DO NOTHING`, runs the action and stores the
response, all in one transaction. A concurrent duplicate blocks on the unique index until the
first commits, then replays the stored response (`Idempotent-Replayed: true`). The same key with a
different body is rejected with 422. If the action fails, the key rolls back with it, so a retry
runs normally. Keys are purged after 24 hours.

**Trade-offs.** One extra insert and update per mutating request. Rejected requests (403, 409) are
not cached but re-evaluated on retry, which is the desired behaviour since the state may have changed.

**Where.** `IdempotencyService`, `client.js`, `useItemMutation`. Tests: `IdempotencyTest`,
`hooks.test.jsx`.

---

## 4. Authorization: team-scoped roles checked in one service; outsiders get 404

**Context.** Users belong to several teams with different responsibilities, and authorization
"must be enforced by the system itself".

**Options considered.**
- Global roles (too coarse: a Lead of Payments should not approve Compliance work).
- Spring `@PreAuthorize` expressions spread over controllers (works, but rules end up scattered
  and are hard to test as a whole).
- A full policy engine (ABAC/OPA): over-engineered for this scope.
- **Per-team roles (`VIEWER < MEMBER < LEAD`) plus a global admin flag, decided in one
  `AuthorizationService` and one `WorkflowPolicy`.**

**Decision.** Spring Security only authenticates (stateless HS256 JWT). Every service method loads
the resource, then asks `AuthorizationService` about the user's role **in that item's team**. List
queries always filter by the caller's teams in SQL. Items of other teams return **404**, identical
to a missing id, so their existence is not leaked; in-team users with too low a role get 403.
Roles are read from the database on each request instead of being embedded in the token, so
removing someone from a team takes effect immediately. Detail responses include a `permissions`
object computed by the same rules, so the UI shows only valid actions, while the server still
checks every request.

**Trade-offs.** One small membership query per request (cacheable later). Workflow rules live in
code (`WorkflowPolicy.RULES`) rather than in configurable data, which keeps them explicit and tested.

**Where.** `AuthorizationService`, `WorkflowPolicy`, `CurrentUserArgumentResolver`.
Tests: `AuthorizationTest`, `WorkflowTest`.

---

## 5. Asynchronous work through a transactional outbox, processed in the same application

**Context.** Notifications should not slow down or fail the primary action, and the brief asks
what happens when async work fails, runs twice or is delayed.

**Options considered.**
- Send notifications synchronously inside the request (couples user latency to secondary work).
- `@Async` or an in-memory queue (work is lost on restart).
- Redis/BullMQ or Kafka (reliable, but another service to run, and enqueueing cannot be atomic
  with the database change).
- **A Postgres `outbox_jobs` table written in the business transaction and drained by a worker.**

**Decision.** The outbox row commits atomically with the change. A `@Scheduled` worker claims one
job at a time with `FOR UPDATE SKIP LOCKED` (several instances never take the same job), runs the
handler and marks it `DONE` in **one transaction**. If the process dies halfway, the transaction
rolls back and the job is retried. Failures are recorded in a separate transaction with exponential
backoff (2s, 4s, ... capped at 5 min), and after `max_attempts` the job is parked as `FAILED` so a
poison message cannot block the queue. Delivery is at-least-once, so handlers are idempotent: a
notification is unique per `(source_job_id, user_id)`.

**Trade-offs.** Holding the job's transaction open while the handler runs is fine for database-only
handlers, but an external call (email, webhook) would need a different pattern (mark in-flight,
call, then confirm). Polling adds up to one second of delay. A job that crashes the whole JVM is
retried without its attempt count increasing.

**Where.** `OutboxWorker`, `NotificationHandlers`. Tests: `OutboxWorkerTest`.

---

## 6. Reads at scale: keyset pagination and Postgres full-text search, with SQL for reads and JPA for writes

**Context.** Tens of thousands of active items and an ever-growing history; the browser must never
load everything, and the server must not filter in memory.

**Options considered.**
- `OFFSET`/`LIMIT` pages with total counts (cost grows with depth; pages shift when items are added).
- A dedicated search engine (Elasticsearch/OpenSearch): better relevance, but another system to run
  and keep in sync.
- **Keyset (cursor) pagination plus Postgres `tsvector` search.**

**Decision.** Lists are ordered by `(updated_at DESC, id DESC)`, and the cursor encodes the last row,
so each page is an index range scan of constant cost and stays correct while new items arrive.
Search uses a generated `tsvector` column (title weighted above description) with a GIN index;
`#123` matches the id. Dashboard views (`mine`, `unassigned_urgent`, `awaiting_my_approval`,
`overdue`) are server-side filters with matching (partial) indexes. Work-item writes use a JPA
entity, for `@Version` and simple mutation code; append-only inserts (history, comments, outbox)
and all list and search queries are explicit SQL through
`NamedParameterJdbcTemplate`, because dynamic filters, keyset conditions and full-text operators
are clearer in SQL than in the Criteria API. Entities reference users and teams by id rather than
associations, which avoids lazy loading and N+1 queries by construction.

**Trade-offs.** No total counts or arbitrary "jump to page 37"; results are ordered by recency, not
search relevance; English stemming only. Two persistence styles to understand.

**Where.** `WorkItemQueries`, `Cursor`, indexes in `V1__schema.sql`. Tests: `HistoryAndListTest`.

---

## What I chose not to build

| Not built | Why |
|-----------|-----|
| WebSockets / live push | Polling (15–30 s) and refetch on focus are enough at this scale, and stale writes are still caught by version checks. Push would be the next step for responsiveness. |
| Email or Slack delivery | The outbox makes this one more handler; in-app notifications prove the mechanism. |
| SSO, sign-up, user and team administration | Users are seeded. A real deployment would sync from the company identity provider. |
| Attachments, item deletion, moving items between teams | Not needed to show the core behaviour; deletion would also weaken the audit trail. |
| Four-eyes approval (approver ≠ assignee) | Easy to add in `WorkflowPolicy`, but it needs a product decision for teams with one Lead (see limitations). |
| Microservices, Redis, Kafka | One application and one database keep failure modes few and explainable. |
