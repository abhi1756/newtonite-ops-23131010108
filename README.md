# Newtonite Ops

An internal web application for coordinating operational work (incidents, customer
issues, payments, compliance requests, tasks) across teams, with server-enforced
workflow and permissions, a complete activity history, and safe behaviour when many
people act on the same item at once.

> Work in progress. See [docs/DESIGN.md](docs/DESIGN.md) and [docs/ASSUMPTIONS.md](docs/ASSUMPTIONS.md).

## Run

Prerequisite: Docker Desktop.

```bash
docker compose up --build
```

* API: http://localhost:8081 (health: `/actuator/health`, API docs: `/api/docs`)
* PostgreSQL: `localhost:5433` (user/password `newtonite`)

Quick check:

```bash
curl -s -X POST localhost:8081/api/auth/login -H "Content-Type: application/json" -d '{"email":"alice@newtonite.test","password":"demo1234"}'
```

## Tests

Prerequisites: JDK 21+ and a running Docker (tests start their own PostgreSQL with Testcontainers).

```bash
cd backend
./mvnw test
```

The suite drives the real HTTP API against a real PostgreSQL and covers the risky behaviour:
simultaneous claims (exactly one winner), stale and simultaneous edits, duplicate and
concurrent requests with the same `Idempotency-Key`, workflow and approval rules,
per-team authorization (404 for outsiders, 403 for insufficient roles), complete history,
and cursor pagination while new items arrive.

## Demo users

All passwords are `demo1234`.

| User | Teams and roles |
|------|-----------------|
| admin@newtonite.test | Global admin |
| alice@newtonite.test | Payments **Lead**, Compliance Member |
| bob@newtonite.test | Payments Member |
| judy@newtonite.test | Payments Member |
| carol@newtonite.test | Platform Engineering **Lead** |
| dave@newtonite.test | Platform Engineering Member, Payments Viewer |
| frank@newtonite.test | Platform Engineering Member, Customer Support Member |
| ivan@newtonite.test | Platform Engineering Viewer |
| erin@newtonite.test | Customer Support **Lead** |
| heidi@newtonite.test | Customer Support Member, Compliance Member |
| grace@newtonite.test | Compliance **Lead** |
