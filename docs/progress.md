# Build progress

Every phase must leave the repository runnable. Results below are from real runs; nothing is
reported as verified unless it was executed.

## Phase 1 — Scaffolding, database, auth, accounts & VPAs ✅ (2026-11-20)

**Delivered**
- Repository layout, `.env.example`, `docker-compose.yml` (PostgreSQL 17, Redis 7.4, Kafka 4.2 KRaft, backend, frontend), Dockerfiles, nginx reverse proxy, GitHub Actions CI.
- Spring Boot 3.5.16 / Java 17 backend: Flyway `V1` (users, accounts, upi_handles, treasury), `V2` (immutable, self-balancing ledger).
- Registration, BCrypt hashing, login with timing-safe failure path, HS256 JWT (signature + expiry + issuer validated), USER / ADMIN_DEMO roles, RFC 7807 errors for 400/401/403/404/409/422.
- Accounts funded via balanced journals from a SYSTEM treasury, VPAs with DB-enforced uniqueness, activation/deactivation, payee resolution, ledger view, ADMIN_DEMO-only dev top-up, idempotent demo seeder.
- React 19 + Vite 8 + Tailwind 4 frontend: landing/login/register, dashboard (balances, accounts, VPAs, ledger, open account, verify payee, admin top-up), sandbox banner.

**Verification actually performed (Windows 11, JDK 21 targeting release 17, Node 24)**

| Check | Result |
|---|---|
| `./mvnw test` (embedded PostgreSQL 17.12 — Docker not installed on the dev machine) | **69 tests, 0 failures, 0 errors** |
| `npm run lint` / `npm test` / `npm run build` | clean / **15 tests passed** / built |
| `./mvnw spring-boot:test-run` + `scripts/smoke-test.sh` and `.ps1` | both passed against the live backend |
| Browser check of the dashboard via Vite dev server | login, balances, ledger, ₹250.50 top-up, payee verification all worked |

**Not yet verified**
- `docker compose up` and the Docker image builds — Docker is not installed on the machine used. The CI `compose` job builds them.
- The CI workflow itself has not run yet (repository not pushed).
- Testcontainers code path (`-Dpaysure.test.db=testcontainers`) — exercised by CI, not locally.

**Phase 1 test coverage of the mandatory list:** #24 (JWT validation & authorization) fully; #25 partially
(ledger invariants, no overdraft, at-most-once journal per reference, concurrency with no lost updates
and no deadlocks). The remaining mandatory cases depend on Phases 2–4.

## Phase 2 — Payments, idempotency, holds, state machine, simulated bank, collect requests ✅ (2026-11-20)

**Delivered**
- Flyway `V3`: `payments`, `payment_attempts`, `payment_state_history` (append-only), `idempotency_records`,
  `collect_requests`, `sim_bank_transfers`, `sim_bank_callbacks`; status/hold consistency enforced by CHECKs.
- `POST /api/payments` with mandatory `Idempotency-Key` (claim via `INSERT … ON CONFLICT DO NOTHING`, committed
  atomically with the payment), canonical request hashing, 409 on payload mismatch, 24 h+ retention job that
  never forgets keys of unfinished payments.
- Reserved funds (`available = balance − reserved`), released or captured exactly once; ledger `CAPTURE_HOLD`
  and `SETTLEMENT` legs; one `PAYMENT_TRANSFER` journal per payment.
- Central `PaymentStateMachine`, guarded transitions under a payment row lock, append-only history with source/reason.
- `BankGateway` / `BankCallbackListener` / `BankInspector` ports; `SimulatedBank` with 10 scenarios, committed
  vs reported outcome, idempotent submit, status lookup, background clock, at-least-once callbacks with backoff,
  seeded reproducible chaos, ADMIN_DEMO fast-forward.
- `POST /api/payments/{id}/check-status` (verify, never re-send); NOT_FOUND → FAILED only after a grace period.
- Collect requests: create / approve (idempotent, ≤ 1 payment per request) / decline / cancel / lazy expiry.
- Business metrics: `payment_requests_total`, `payment_success_total`, `payment_failure_total`,
  `payment_pending_total`, `duplicate_requests_prevented_total`, `bank_callbacks_ignored_total`,
  `payment_processing_duration_seconds` (no high-cardinality labels).
- Frontend: Send Payment (safe retry reuses the key; admin scenario picker), Request Money, Payment History
  (status filter, 5 s refresh), Transaction Details (timeline, bank attempts, simulated bank's own record,
  check-status, fast-forward; 2 s refresh while non-terminal).
- Docs: [payments.md](payments.md), [why-pending.md](why-pending.md); API, database, architecture, ADR updated.

**Bug found and fixed during this phase:** `AuditedEntity` used a primitive `@Version`, so Spring Data treated
new entities with assigned UUIDs as existing and `merge`d them into a copy; later changes to the original
instance were silently lost. Phase 1 code was unaffected only because it always used the returned copy. The
version is now a nullable `Long` exposed raw through its getter. Caught by the payment tests' invariant checks.

**Verification actually performed**

| Check | Result |
|---|---|
| `./mvnw verify` (embedded PostgreSQL 17.12) | **128 tests, 0 failures, 0 errors** |
| `npm run lint` / `npm test` / `npm run build` | clean / **17 tests passed** / built |
| Live backend (`spring-boot:test-run`, V3 migrated over existing Phase 1 data) | started; seeded data intact |
| `scripts/smoke-test.sh` and `.ps1` (now incl. payment, replay → 200, key reuse → 409) | both passed |
| Live lost-acknowledgement run via API | PENDING with ₹500 held → replay 200 → check-status SUCCESS once → 2nd check NO_CHANGE; balances moved exactly once |
| Live duplicate-callback run (background scheduler, 4 callbacks) | SUCCESS, 1 applied / 3 ignored |
| Live collect request approved twice | same payment both times |
| `/actuator/prometheus` | payment metrics present with only the `application` label |
| Browser (pages read as text; the pane was hidden so clicks could not be driven) | History and Transaction Details render live data; one UI bug found and fixed (mismatch callout shown for normal ACCEPTED flows) |

**Mandatory test cases now covered:** 1–15, 24, 25 (case 13 delayed success, 14 duplicate callback incl.
concurrent redelivery, 15 out-of-order). Remaining: 16–18 (reconciliation worker, Phase 3), 19–20 (Kafka/outbox,
Phase 4), 21–22 (webhooks, Phase 4), 23 (Redis, Phase 4).

**Not yet verified**
- Docker Compose / images and the CI workflow (no Docker on this machine; repository not pushed).
- Clicking through Send Payment / Request Money in a visible browser (covered by component tests, not by E2E).

**Known limitations (deliberate, documented)**
- If a collect-request payment fails (e.g. insufficient funds), the request stays APPROVED with a FAILED payment.
- No automatic reconciliation yet: PENDING payments resolve via callbacks or the manual status check until Phase 3.
- No rate limiting or risk checks on payments yet (Phases 4–5).

## Phase 3 — Reliability Lab, self-healing reconciliation, reliability score ✅ (2026-11-20)

**Delivered** (details in [reconciliation.md](reconciliation.md))
- Flyway `V4`: reconciliation lease/attempt/review columns on `payments`, stored idempotent-replay counts,
  `reconciliation_runs`, `reconciliation_items`, `fault_scenarios` (13 seeded), `lab_runs`, `reliability_snapshots`.
- `ReconciliationWorker`: lease-based claiming (`FOR UPDATE SKIP LOCKED`), status verification by stable reference
  (query-only, no path to re-send), exactly-once finalisation, `STILL_PENDING` / `UNKNOWN` left pending, escalation to manual
  review, one-time post-settlement cross-check with conflict flagging, per-run ledger/hold invariant checks, run and item
  records, aggregate report, manual-review queue; scheduler every 15 s + manual trigger; metrics.
- Simulated-bank status-API outage switch (so "outcome unknown" can be demonstrated honestly).
- Reliability Lab: scenario catalogue API, start / act (reconcile, fast-forward, restore status API), merged event timeline,
  bank committed-vs-reported view, ledger legs, balances before / after fault / now, generated explanation.
- Explainable reliability score from stored events only; components without data (including webhooks, until Phase 4) are
  excluded, never scored 100; snapshots + history; explicit "demo score, not an industry benchmark" disclaimer.
- Frontend: **Reliability Lab** and **Reconciliation** pages (demo admin only in the UI; enforced server-side on
  `/api/admin/**`), score card with Recharts history chart.

**Bug found and fixed this phase:** the lab's "Payment request sent" event was stamped when the run row was inserted (after the
payment), so it sorted after the payment's own events. Found by reading live output; my first test only asserted that an
already-sorted list was sorted. It now records the real start time and the test asserts the first event is the client's.

**Verification actually performed**

| Check | Result |
|---|---|
| `./mvnw verify` (embedded PostgreSQL 17.12) | **151 tests, 0 failures, 0 errors** (128 before Phase 3 + 23 new) |
| `npm run lint` / `npm test` / `npm run build` | clean / **18 tests passed** / built |
| Live backend: V4 migrated over existing data; lab "Lost acknowledgement" started over the API | PENDING with ₹500 held, no ledger posting |
| Same run, left alone | the **scheduled** worker resolved it in ≈ 14.5 s: SUCCESS, one journal, hold released, score → 87 |
| Live report and metrics | `runs`/`resolved` counts correct; `reconciliation_runs_total`, `reconciliation_resolved_total` present |

**Mandatory test cases now covered:** 1–18, 24, 25. Case 16 (reconciliation success: committed-but-unacknowledged payment
finalised once, not re-sent), 17 (unknown outcome stays PENDING; status API outage then recovery), 18 (four concurrent workers;
each payment claimed by exactly one; a live lease blocks a second worker; an expired lease is reclaimed). Also covered:
escalation exactly once, fresh-payment minimum age, conflict flagging without auto-correction, ledger-drift detection,
score drop-and-recovery (100 → 72 → 89), admin-only access. Remaining: 19–20 (Kafka/outbox), 21–22 (webhooks),
23 (Redis) — Phase 4.

**Not verified**
- Docker Compose / images and CI (no Docker on this machine; repository not pushed).
- The new pages were not driven by clicks in a visible browser (pane hidden); the Lab flow is covered by a component test
  with mocked responses, and the same API calls were exercised live with curl.
- The "publish an event when a payment is resolved" step: needs the Phase 4 outbox. Resolutions are logged and counted only.

**Known limitations (deliberate, documented)**
- The status-API outage switch is global to the simulator (reset by any lab run that does not need it down).
- Reliability-score windows are system-wide, not per user; the "success rate" counts bank declines against the platform.
- Escalated payments stay PENDING; there is no operator "force resolve" action by design.

## Phase 4 — Transactional outbox, Kafka, webhooks, Redis ✅ (2026-11-20)

**Delivered** (details: [events.md](events.md), [webhooks.md](webhooks.md), [redis.md](redis.md))
- Flyway `V5`: `outbox_events`, `inbox_events`, `webhook_endpoints` (no secret column), `webhook_deliveries` (`UNIQUE(endpoint, event)`),
  `webhook_delivery_attempts`, `webhook_receipts`.
- **Transactional outbox**: payment created/completed/failed and reconciliation-completed events are written in the same PostgreSQL
  transaction as the business change (`Propagation.MANDATORY`). Ordered, advisory-locked, at-least-once publisher with backoff and a DEAD limit;
  `EventPublisher` port with a Kafka implementation (`acks=all`, idempotent producer, key = aggregate id).
- **Idempotent consumer**: inbox row + webhook deliveries in one transaction, offset committed after (AckMode.RECORD), retry 3× then `<topic>.DLT`.
- **Webhooks**: HMAC-SHA256 over `timestamp.rawBody`, replay window, constant-time verify; durable deliveries and attempt log; equal-jitter bounded
  exponential backoff; dead-letter + manual retry; **SSRF guard** at registration and again before every delivery; derived (unstored) secrets with rotation;
  built-in sandbox receiver that processes each event once; owner-scoped management API.
- **Redis**: atomic Lua rate limiting (payments per user and per IP, collect requests, login/register per IP) with 429 + `Retry-After`; read-through
  payment-status cache invalidated after commit; fail-fast circuit breaker with in-memory fallback.
- Reliability score: the webhook-delivery component is now **measured** from stored deliveries (was "not measurable").
- Frontend: **Webhooks** page (register, one-time secret, enable/disable/rotate, delivery log with attempt history and manual retry; operator panels for the
  outbox pipeline and the sandbox receiver). Compose, `.env.example`, nginx (overwrites `X-Forwarded-For`) updated; the no-Docker dev runner now starts an
  in-process Kafka broker and a real `redis-server`.

**Bugs found and fixed this phase** (each caught by a test or a live run, not by inspection)
1. *Dead-letter topic name.* Spring Kafka's recoverer defaults to `<topic>-dlt`, but the topics created (and documented) are `<topic>.DLT`; on a broker with auto-creation
   disabled (docker-compose) the dead-letter publish would have failed. The destination is now explicit. Caught by the poison-record integration test.
2. *Prometheus metric collision.* A counter named `webhook.deliveries.created` collided with `webhook.deliveries{outcome}` (Prometheus reserves the `_created` suffix) and the
   labelled one silently vanished. Renamed to `…enqueued`. Caught by reading `/actuator/prometheus` during the live run.
3. *Bean cycle*: the Lettuce customizer lived in a config class that depended on Redis. Split out. Caught at the first context start.
4. *Test isolation* (not product bugs): a cached Kafka test context kept publishing/consuming other classes' events from the shared database (now `@DirtiesContext`), and
   leftover demo-admin endpoints received every event (now disabled between tests).

**Verification actually performed**

| Check | Result |
|---|---|
| `./mvnw verify` — real PostgreSQL 17, real in-process Kafka broker, real `redis-server` (no Docker on this machine) | **236 tests, 0 failures, 0 errors** (151 before Phase 4 + 85 new) |
| `npm run lint` / `npm test` / `npm run build` | clean / **20 tests passed** / built |
| Live stack (`spring-boot:test-run`): embedded PostgreSQL + KRaft Kafka + redis-server; register sandbox receiver (`fail=2`), pay | delivery `RETRYING → RETRYING → SUCCESS`; attempt log `500, 500, 200`; receiver `calls = 3, processed once`; outbox `pending 0, published 3, inbox 3` |
| Live SSRF checks | `169.254.169.254`, `10.0.0.5`, `127.0.0.1`, plain `http://` all refused with `WEBHOOK_URL_BLOCKED` |
| Live rate limiting | 29 × 201 then 429 with `Retry-After`, `X-RateLimit-Limit: 30`, `X-RateLimit-Remaining: 0` |
| Live status cache | `X-Cache: MISS` then `HIT` |
| Live metrics | `outbox_*`, `events_duplicates_ignored_total`, `webhook_deliveries_total{outcome}`, `webhook_delivery_failures_total`, `ratelimit_rejected_total{policy}`, `payment_status_cache_total{result}`, `redis_errors_total` |

**Mandatory test cases now covered: all 25.** 19 (duplicate Kafka events: handler-level, 8-way concurrent, rolled-back transaction, and through a real broker),
20 (outbox publisher failure and recovery, ordering, poison row, concurrent publishers), 21 (webhook retries with backoff, dead-letter, manual retry, flaky receiver
processed once), 22 (invalid signature, tampered body, wrong secret, stale timestamp, mismatched ids — nothing recorded), 23 (a real `redis-server` stopped and
restarted mid-test: payments keep working, limits degrade to in-memory, Redis resumes).

**Not verified**
- Docker Compose, the Docker images and the CI workflow (no Docker here; repository not pushed). Testcontainers paths for Kafka/Redis are written for Redis
  (`TestRedis`) and Postgres but were not executed; Kafka tests use the in-process broker only.
- A real-HTTP-server test class (RANDOM_PORT) was not added; real HTTP delivery was verified in the live run instead (`HttpWebhookTransport` → sandbox receiver).
- The new Webhooks page was not clicked through in a visible browser (pane hidden); it has component tests and every API call it makes was exercised live with curl.
- Reconciliation, Reliability Lab and Webhooks pages remain untested by Playwright (Phase 6).

**Known limitations (deliberate, documented)**
- Delivery to webhook receivers is at-least-once; ordering across events is not guaranteed. Fixed-window rate limits allow a 2× burst at a boundary.
- DNS-rebinding between the SSRF check and the connection is narrowed, not eliminated. The local allow-list (`localhost:8080`) is for demos only.
- A cache write can briefly store a stale unfinished status (≤ 30 s TTL); final states cannot be stale.
- The head-of-line outbox publisher pauses on a failing row until it is DEAD (20 attempts); throughput is not a goal here.
- `risk.alert` has a topic, consumer routing and webhook support but no producer until Phase 5. The optional Redis "read-side metrics cache" was not built.

## Phase 5 — Risk engine, monitoring, Grafana ✅ (2026-11-20)

**Delivered** (details: [risk.md](risk.md), [monitoring.md](monitoring.md))
- Flyway `V6`: `risk_alerts` (score/level/action CHECKs; a blocked attempt cannot reference a payment) and a partial index for "known recipient" lookups.
- **Rule-based risk engine** — a pure, unit-tested `RiskEngine` with six configurable rules (high velocity, rapid repeats, multiple failures, large amount, new recipient, new-recipient
  burst), capped 0–100 score, level, suggested action and a line-by-line explanation. **No machine-learning model is used or claimed.**
- Evaluated in `PaymentIntake` *after* the idempotency key is claimed, so concurrent duplicates are scored once; alert + `risk.alert` outbox event commit with the payment.
  Default mode `ALERT` never refuses a payment; `ENFORCE` refuses ≥ block score with `422 RISK_BLOCKED`, rolls back the key claim, but records the refusal in a separate transaction.
- APIs: what-if `POST /api/risk/assess` (records nothing), own alerts, config, admin list and review. `risk.alert` events reach the affected user's webhooks (and demo admins').
- **System Monitoring** API and page: 24 h payment figures, median/P95 latency, backlog and risk counts from PostgreSQL; process counters labelled "since start"; dependency health
  with a real Redis `PING` and Kafka honestly labelled as *inferred from the outbox*; 30 one-minute series charted with Recharts.
- **Prometheus + Grafana** in Compose (provisioned datasource and a 16-panel "payments, failures and recovery" dashboard); `scripts/check-dashboard-metrics.py` verifies every queried metric is exposed.
- Required metrics registered at startup (so they exist at 0): fixed `webhook_delivery_failures_total`, added risk / rate-limit / cache series. A test scrapes the endpoint for required names and for unbounded labels.
- Frontend: **Risk Alerts** (alerts with explanations, simulator, rules and weights, admin review) and **Monitoring** pages; the navigation no longer has placeholder entries — every item is a working page.

**Bugs / defects found and fixed this phase**
1. `webhook_delivery_failures_total` (a required metric) only appeared after the first failure; now registered eagerly. Found by writing the metrics-contract test.
2. Test infrastructure: Spring Boot disables the Prometheus registry in tests (`@AutoConfigureObservability` needed); two auth-limit tests could pick the same random IP (~0.5 % flake) — now fixed addresses.

**Verification actually performed**

| Check | Result |
|---|---|
| `./mvnw verify` — real PostgreSQL, in-process Kafka broker, real `redis-server` | **259 tests, 0 failures** (236 before Phase 5 + 23 new) — final run on the finished code |
| `npm run lint` / `npm test` / `npm run build` | clean / **23 tests passed** / built |
| Live stack: 9 quick payments | 8th and 9th each raised an alert (score 55, MEDIUM, REVIEW, not blocked) with the explanation "9 transfers initiated in 60 seconds (threshold 8) (+35) … (+20)"; payments all SUCCESS |
| Live what-if | 40/100: large amount (+25) and new recipient (+15) explained; nothing recorded |
| Live admin review | OPEN → ACKNOWLEDGED; open count fell 2 → 1 |
| Live monitoring | real totals, p50/p95 = 213 / 2838 ms, Redis `PING PONG`, 30 buckets per series |
| `check-dashboard-metrics.py` against the live scrape | 22 distinct metrics used by 16 panels, **all exposed** |
| Dashboard JSON and all YAML (compose, Prometheus, Grafana provisioning) | parse correctly |

**Not verified**
- **Docker Compose, Prometheus and Grafana were never started** (no Docker here): provisioning and panel rendering are unverified. Only the metric *names* and config syntax were checked.
- The Risk Alerts and Monitoring pages were not clicked through in a visible browser (pane hidden); they have component tests and every API they call was exercised live.
- Playwright E2E, CI execution and the Docker images remain for Phase 6.

**Known limitations (deliberate, documented)**
- Risk rules see only PaySure history; fixed thresholds can misfire on legitimate bursts, which is why the default never blocks.
- Process counters reset on restart (Grafana uses `rate()`); the Kafka status is inferred, not probed. Monitoring figures are system-wide and admin-only.

## Phase 6 — E2E, acceptance script, CI, documentation ✅ (2026-11-21)

**Delivered**
- Playwright (`frontend/e2e/`, 16 tests + a gated screenshot generator): auth and access control, pay through the UI (result, timeline, history, both balances),
  request-money approval, a **lost response retried safely with the same `Idempotency-Key`** (charged once), validation, Lab lost-acknowledgement → reconciliation and
  retry storm, webhook retries with the once-only secret banner, SSRF refusal, risk simulator and burst alert, monitoring page. `npm run e2e` starts the backend
  (embedded PostgreSQL/Kafka/Redis) and Vite itself; `E2E_BASE_URL` targets a running Compose stack.
- `scripts/acceptance_demo.py`: executes acceptance criteria A–H against a live backend (fresh users, 10 real concurrent requests for B).
- CI: new `e2e` job (compose up, acceptance script, dashboard-metric check, Prometheus targets and Grafana dashboard via API, Playwright with Chromium, artifacts).
- UI fix found by the E2E run: the payee name is now looked up shortly after typing stops (previously only on blur).
- Docs: README restructured (business problem, diagrams, idempotency, recovery table, screenshots, future work), `docs/testing.md` (25-case matrix), ADRs 0002–0004,
  8 real screenshots in `docs/screenshots/`.

**Verification actually performed (Windows 11, no Docker)**

| Check | Result |
|---|---|
| `./mvnw verify` (embedded PostgreSQL, in-process Kafka, real `redis-server`) | **259 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** |
| `npm run lint` / `npm test` / `npm run build` | clean / **23 passed** / built (chunk-size warning only) |
| Playwright (`E2E_CHANNEL=msedge`, real Edge, real backend) | **16 passed**, 2 skipped (screenshot generator, gated by `SCREENSHOTS=1`) |
| `python scripts/acceptance_demo.py` | **8/8** checks (A–H) passed |
| `python scripts/check-dashboard-metrics.py` | all 22 metrics used by the 16 Grafana panels are exposed |
| E2E specs type-checked with `tsc --strict` | no errors |

**Acceptance criteria**

| | Status | Evidence |
|---|---|---|
| A normal transfer | ✅ | acceptance A, `case1`, Playwright payments |
| B 10 identical concurrent requests → one transfer | ✅ | acceptance B, `case8` |
| C lost-ack PENDING | ✅ | acceptance C, `case11`, Lab E2E |
| D reconciliation → SUCCESS without repeating | ✅ | acceptance D, `case16`, Lab E2E |
| E duplicate callback, no extra posting | ✅ | acceptance E, `case14` |
| F failed webhook retried, once at business level | ✅ | acceptance F, `case21`, webhooks E2E |
| G transparent risk explanation | ✅ | acceptance G, `RiskEngineTest`, risk E2E |
| H working dashboard with real metrics | ✅ for the in-app page and the metric check; Grafana itself **not viewed** | monitoring E2E, acceptance H |
| I all tests pass, results recorded | ✅ for what was run here (above) | this table |
| J reproducible Docker demo | ⚠️ **not verified** | no Docker on the dev machine |

**Not verified**
- `docker compose up --build`, the Docker images, the Prometheus/Grafana containers (the dashboard JSON was only checked against the backend's metric names) and the CI
  workflow including the new `e2e` job: never executed. The first run may need small fixes.
- Testcontainers paths for PostgreSQL and Redis never executed; no Testcontainers Kafka path exists (the in-process broker is used).
- Playwright ran on Edge via `channel`, not on Playwright's bundled Chromium (the CI job installs Chromium and will be its first use). No Firefox/WebKit/mobile runs.
- Per-IP auth rate limiting is relaxed for E2E runs only (`PAYSURE_RATE_LIMIT_AUTH_PER_IP_LIMIT`); it is covered by backend tests, not by the browser tests.
- No load, soak or long-running (hours) reconciliation testing; screenshots show test traffic on a development database.
