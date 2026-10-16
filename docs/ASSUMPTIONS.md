# Assumptions and Open Questions

The brief deliberately leaves product decisions open. This file lists every open
question we identified and the assumption we made. Items marked **(PM)** are the
ones we would confirm with a product manager first.

## Product

| # | Question | Assumption |
|---|----------|------------|
| 1 | What is a work item? | A single unit of operational work owned by exactly one team: title, description, type, priority, status, assignee, due date. |
| 2 | Which types exist? | `INCIDENT`, `CUSTOMER_ISSUE`, `ENGINEERING`, `PAYMENT`, `COMPLIANCE`, `TASK`. Fixed enum for v1. |
| 3 | Which priorities exist? | `LOW`, `MEDIUM`, `HIGH`, `URGENT`. "Urgent" in dashboard views means `HIGH` or `URGENT`. |
| 4 | Does every item need approval? **(PM)** | No. Only `PAYMENT` and `COMPLIANCE` items must pass through `AWAITING_APPROVAL`. Other types go `IN_PROGRESS → RESOLVED` directly. |
| 5 | What are the workflow states? | `OPEN → IN_PROGRESS → (AWAITING_APPROVAL) → RESOLVED → CLOSED`. A Lead may reject an approval (back to `IN_PROGRESS`), a resolved item may be reopened, and `CLOSED` is final. |
| 6 | Can an item move between teams? **(PM)** | No, not in v1. Team ownership is fixed at creation. |
| 7 | Can an item have several assignees? | No. Exactly zero or one assignee, which keeps "who is responsible" unambiguous. |
| 8 | Can items be deleted? | No. Items are closed, never deleted, so history is never lost. |
| 9 | Do comments get edited or deleted? | No. Comments are append-only, like the activity log. |
| 10 | Is a due date mandatory? | No. Optional; overdue items surface on the dashboard. |

## Identity and access

| # | Question | Assumption |
|---|----------|------------|
| 11 | How do users sign up? | They don't. Users and teams are seeded (an internal tool would sync from an identity provider; SSO is out of scope). |
| 12 | What roles exist? | Per-team roles `VIEWER`, `MEMBER`, `LEAD`, plus a global `admin` flag on the user. A user can hold different roles in different teams. |
| 13 | Is Admin per team or global? | Global. Admins may do anything in every team (used for support and oversight). |
| 14 | Who can approve? **(PM)** | Only a `LEAD` of the item's team (or an admin). |
| 15 | Who can edit an item? | Its assignee, its creator while it is still unassigned, a Lead of its team, or an admin. |
| 16 | Who can assign an item to someone else? | Leads and admins. Members can only claim unassigned items for themselves. |
| 17 | What does a user outside the team see? | Nothing. Items of other teams return **404** (not 403) so their existence is not leaked. |
| 18 | How long does a login last? | 8-hour JWT. No refresh tokens in v1. |

## Concurrency and behaviour

| # | Question | Assumption |
|---|----------|------------|
| 19 | Two users claim the same item at once? | First wins; second gets `409 Conflict` naming the winner. Claiming an item you already own is a no-op success. |
| 20 | User edits an outdated version? | Rejected with `409` and the latest item; the UI asks the user to review and retry. No automatic merging. |
| 21 | User repeats a request (double-click, retry)? | Mutating requests carry an `Idempotency-Key`; a repeat returns the original response without redoing the work. Keys are kept for 24 hours. |
| 22 | Must other users see changes instantly? | No. Polling every ~15s and refetch-on-focus is enough for v1 (no websockets). Stale writes are still caught by version checks. |
| 23 | Which work is asynchronous? | Notifications (new assignee, approval needed, approval decision). The primary action never waits for them. |
| 24 | Are notifications emailed? | No. They are stored in-app. Email delivery would be another outbox handler. |
