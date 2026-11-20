# Testing

PaySure's tests assert **money invariants in SQL** (journals net to zero, balances equal net ledger movement, no negative available
funds, reserved funds equal held payments, one ledger transfer per SUCCESS payment), not just HTTP status codes.

Recorded results of the latest runs live in [progress.md](progress.md). This page explains *what* is tested and *how to run it*.

## Layers

| Layer | Tooling | Runs |
|---|---|---|
| Backend unit + integration + concurrency | JUnit 5, Spring Boot Test, MockMvc, real PostgreSQL / Kafka / Redis | `cd backend && ./mvnw verify` |
| Frontend unit / component | Vitest + React Testing Library | `cd frontend && npm test` |
| Browser end-to-end | Playwright against the real UI and real backend | `cd frontend && npm run e2e` |
| Acceptance script (criteria A–H) | stdlib Python against a running backend | `python scripts/acceptance_demo.py` |
| Dashboard ↔ metrics consistency | `scripts/check-dashboard-metrics.py` | needs a running backend |

### Which real infrastructure the backend tests use

| Dependency | With Docker (CI) | Without Docker (the machine this was built on) |
|---|---|---|
| PostgreSQL 17 | Testcontainers (`-Dpaysure.test.db=testcontainers`, forced in CI so it fails loudly if Docker is missing) | zonky embedded PostgreSQL (real server binaries) |
| Kafka | — | in-process KRaft broker via `@EmbeddedKafka` |
| Redis | Testcontainers (`TestRedis`) | a real `redis-server` process (`it.ozimov:embedded-redis`) |

Be aware: the original brief asked for Testcontainers for PostgreSQL, Kafka **and** Redis. The Kafka tests use the in-process broker only;
a Testcontainers Kafka path was **not written**. The Testcontainers paths for PostgreSQL and Redis exist in code but have **never been
executed**: there is no Docker on the development machine and the CI workflow has not run yet (see `progress.md`).

## The 25 mandatory test cases

Test methods are named `caseN_…` where the case has a single clear test. Paths are under `backend/src/test/java/com/paysure/`.

| # | Case | Where |
|---|---|---|
| 1 | Successful payment | `payment/PaymentIntegrationTest#case1_successfulPayment` |
| 2 | Insufficient balance | `PaymentIntegrationTest#case2_insufficientBalanceFailsWithoutMovingMoney` |
| 3 | Invalid VPA | `PaymentIntegrationTest#case3_invalidVpa` |
| 4 | Invalid amount | `PaymentIntegrationTest#case4_invalidAmounts` |
| 5 | Unauthorized payer | `PaymentIntegrationTest#case5_unauthorizedPayerAndSelfTransfer` |
| 6 | Duplicate idempotency key | `PaymentIntegrationTest#case6_duplicateIdempotencyKeyReturnsTheSamePayment` |
| 7 | Same key, conflicting payload | `PaymentIntegrationTest#case7_sameKeyWithDifferentPayloadIsAConflict` |
| 8 | Simultaneous duplicate requests | `payment/PaymentConcurrencyTest#case8_tenIdenticalConcurrentRequestsProduceExactlyOneTransfer` |
| 9 | Two competing payments, one balance | `PaymentConcurrencyTest#case9_twoCompetingPaymentsAgainstOneBalance_onlyOneCanWin` |
| 10 | Bank timeout | `payment/BankFaultScenarioTest#case10_…` |
| 11 | Bank timeout after successful commit | `BankFaultScenarioTest#case11_…` |
| 12 | Bank unavailable | `BankFaultScenarioTest#case12_…` |
| 13 | Delayed success confirmation | `BankFaultScenarioTest#case13_…` |
| 14 | Duplicate bank callback | `BankFaultScenarioTest#case14_…`, `#case14b_concurrentRedeliveryOfTheSameCallback_postsOnce` |
| 15 | Out-of-order callback | `BankFaultScenarioTest#case15_…` |
| 16 | Reconciliation success | `reconciliation/ReconciliationIntegrationTest#case16_…` |
| 17 | Reconciliation with unknown outcome | `ReconciliationIntegrationTest#case17_unknownOutcomeStaysPendingAndIsNeverGuessed` |
| 18 | Duplicate reconciliation workers | `ReconciliationIntegrationTest#case18_duplicateReconciliationWorkersNeverActOnTheSamePayment` |
| 19 | Duplicate Kafka events | `events/KafkaEventsIntegrationTest#case19_…` (real broker); `notification/NotificationConsumerTest` (handler level) |
| 20 | Outbox publisher failure and recovery | `events/OutboxIntegrationTest#case20_…` |
| 21 | Webhook retries | `notification/WebhookDeliveryIntegrationTest#case21_…` (+ `BackoffPolicyTest`) |
| 22 | Invalid webhook signature | `WebhookDeliveryIntegrationTest#case22_…` (+ `WebhookSignerTest`) |
| 23 | Redis temporary unavailability | `ratelimit/RedisOutageTest#case23_…` (a real `redis-server` is stopped and restarted) |
| 24 | JWT validation and authorization | `identity/AuthIntegrationTest` (missing / garbage / expired / wrong-key / wrong-issuer tokens, role checks) |
| 25 | Ledger reconciliation and no double debit | `PaymentConcurrencyTest#case25_…`, `ledger/LedgerIntegrationTest`, plus the SQL invariant checks run by `IntegrationTest` after every test |

Further suites beyond the 25: SSRF guard, rate limiting (Lua limiter, auth limits), status cache, risk engine (pure rules + ALERT/ENFORCE
integration), monitoring endpoints, demo seeder, idempotency retention, reliability score, and the Reliability Lab scenarios.

## Browser end-to-end tests (Playwright)

`frontend/e2e/*.spec.ts` drives the real React UI against the real backend: registration and sign-in, paying through the UI (result,
timeline, history, both balances), request-money approval, a **lost response retried safely with the same `Idempotency-Key`**, input
validation, admin-page access control, the Lab's lost-acknowledgement → reconciliation story and retry storm, webhook retries with the
once-only secret banner, the SSRF refusal, the risk simulator and a burst-triggered alert, and the monitoring page.

```bash
# Locally, no Docker: starts the backend (embedded PostgreSQL + Kafka + Redis) and the Vite dev server by itself.
cd frontend && npx playwright install chromium     # or use an installed browser: E2E_CHANNEL=msedge / chrome
npm run e2e                                         # E2E_CHANNEL=msedge npm run e2e

# Against a running Docker Compose stack (what CI does):
E2E_BASE_URL=http://localhost:3000 npx playwright test
```

Per-IP auth rate limiting is deliberately relaxed for these runs (`PAYSURE_RATE_LIMIT_AUTH_PER_IP_LIMIT`, default `20`/minute in the product)
because one browser registers many throw-away users. Rate limiting itself is covered by the backend tests.

`SCREENSHOTS=1 npx playwright test screenshots` regenerates `docs/screenshots/` from the running app.

## Acceptance script

`python scripts/acceptance_demo.py [base-url]` registers fresh users and checks, against a live backend:

| Criterion | What it asserts |
|---|---|
| A | A normal transfer debits the payer and credits the payee exactly once |
| B | 10 concurrent requests with one `Idempotency-Key` → 1 payment, 1×201 + 9×200 replays, charged once |
| C | Lost acknowledgement → PENDING, funds held, no ledger posting, the bank had actually committed |
| D | Reconciliation → SUCCESS with one balanced journal, no re-send; running it again changes nothing |
| E | Duplicate callbacks → still exactly one debit and one credit |
| F | A webhook endpoint failing twice is retried (500, 500, 200); the receiver processed the event once |
| G | The risk assessment lists the triggered rules and a disclaimer |
| H | The monitoring summary has real data and Prometheus exposes the dashboard's metric families |

Criteria **I** (all tests pass, results recorded) and **J** (reproducible Docker demo) are covered by running the suites and
`docker compose up`; see the status table in [progress.md](progress.md) for what has actually been executed.
