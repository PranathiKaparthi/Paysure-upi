# Monitoring: metrics, Grafana and the System Monitoring page (Phase 5)

## Prometheus metrics

`/actuator/prometheus` (Micrometer). The required business metrics, all registered at startup so they exist (at 0) before the first event:

| Metric | Meaning |
|---|---|
| `payment_requests_total` | new payment intents accepted (idempotent replays are not counted) |
| `payment_success_total` / `payment_failure_total` | payments finalised SUCCESS / FAILED |
| `payment_pending_total` | payments that entered PENDING |
| `payment_processing_duration_seconds` | histogram: intent → first bank outcome (`_bucket`, `_count`, `_sum`) |
| `reconciliation_runs_total` / `reconciliation_resolved_total` | runs completed / payments resolved by reconciliation |
| `duplicate_requests_prevented_total` | requests answered from an existing idempotency record |
| `webhook_delivery_failures_total` | failed webhook delivery attempts |

Also: `bank_callbacks_ignored_total`, `outbox_pending` (gauge), `outbox_published_total`, `outbox_publish_failures_total`, `outbox_dead_total`,
`events_duplicates_ignored_total`, `webhook_deliveries_total{outcome}`, `webhook_deliveries_enqueued_total`, `ratelimit_rejected_total{policy}`,
`payment_status_cache_total{result}`, `redis_errors_total`, `risk_evaluations_total{level}`, `risk_alerts_total{level}`, `risk_blocked_total`, plus Spring/JVM metrics.

**Cardinality is bounded.** Business metrics carry only `application` and a handful of enumerated tags (`outcome`, `level`, `policy`, `result`). A test scrapes the
endpoint and fails if any user id, payment id, bank reference or VPA appears in a time series.

## Grafana and Prometheus (docker-compose)

`docker compose up` also starts **Prometheus** (http://localhost:9090, scraping `backend:8080/actuator/prometheus` every 15 s) and **Grafana** (http://localhost:3001, user `admin`,
password from `GRAFANA_ADMIN_PASSWORD` in `.env`; no anonymous access, no sign-up). Everything is provisioned from `monitoring/`:

```
monitoring/
├── prometheus/prometheus.yml
└── grafana/
    ├── provisioning/datasources/prometheus.yml     # datasource uid "prometheus"
    ├── provisioning/dashboards/dashboards.yml      # loads /var/lib/grafana/dashboards
    └── dashboards/paysure.json                     # "PaySure — payments, failures and recovery"
```

The dashboard has 16 panels in three rows:

- **Payments** — requests/min, success ratio (1 h), payments by outcome, processing-duration p50/p95 (`histogram_quantile` over the bucket series), duplicates prevented, ignored callbacks, HTTP requests by status.
- **Failures and recovery** — *PENDING entered vs reconciliation resolved* (recovery keeps up when the second follows the first), reconciliation runs, webhook deliveries by outcome and failed attempts, outbox backlog, outbox publish rate/failures, Redis errors and 429s.
- **Risk and platform** — risk alerts by level, status-cache hit/miss, JVM heap.

Counters reset when the backend restarts; every panel uses `rate()`/`increase()`, which handle resets.

`scripts/check-dashboard-metrics.py [scrape-url]` verifies that **every metric the dashboard queries exists in a live scrape** (it fails if a panel would be empty). It was run against the live
backend: 22 distinct metrics, all present. *Not verified:* Grafana itself was not started (no Docker here), so panel rendering and provisioning have not been seen working.

## System Monitoring page (demo admin)

`GET /api/admin/monitoring/summary` and `/series`, shown on the **Monitoring** page (refreshes every 5 s):

- **From PostgreSQL (survives restarts):** payments in the last 24 h by status, success rate, median / P95 completion latency, unresolved payments and the oldest one, outbox and webhook backlog,
  open / high / medium / refused risk alerts, and 30 one-minute buckets of payments (by current status), webhook attempts and reconciliation results — charted with Recharts.
- **From this process's registry (since process start):** the counters above, labelled as such.
- **Dependencies, honestly probed:** PostgreSQL (query), Redis (a real `PING`, or *DEGRADED* while the circuit breaker is open), simulated bank status API (the Lab's outage switch), reconciliation
  worker (age of the last run) and Kafka — which is **inferred** from outbox lag and labelled "not a direct broker probe".
- The reliability score and its history chart (see [reconciliation.md](reconciliation.md)).

The Prometheus endpoint is unauthenticated for local scraping; restrict it at the network layer outside a demo.
