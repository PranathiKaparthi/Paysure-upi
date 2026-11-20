# API examples (Phases 1–2)

Interactive docs: <http://localhost:8080/swagger-ui.html> · OpenAPI JSON: `/v3/api-docs`.
All amounts are **integral paise** (₹250.50 = `25050`). All errors are RFC 7807 problem bodies:

```json
{ "type": "https://paysure.sandbox/errors/vpa-taken", "title": "Conflict", "status": 409,
  "detail": "The VPA rahul@paysure is already registered.", "code": "VPA_TAKEN",
  "timestamp": "2026-11-20T09:20:01Z", "requestId": "1f0c…" }
```

## Authentication

```bash
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"neha@example.com","password":"Passw0rd1","fullName":"Neha Rao"}'
# 201 {"id":"…","email":"neha@example.com","fullName":"Neha Rao","role":"USER",…}

curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"neha@example.com","password":"Passw0rd1"}'
# 200 {"accessToken":"eyJ…","tokenType":"Bearer","expiresIn":3600,"user":{…}}

curl localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
```

## Accounts and VPAs

```bash
# Open an account (funded from the sandbox treasury) and claim a VPA in one transaction
curl -X POST localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"displayName":"Savings","vpaHandle":"neha"}'
# 201 {"id":"…","maskedAccountNumber":"XXXXXXXX4821","balancePaise":1000000,"reservedPaise":0,
#      "availablePaise":1000000,"vpas":[{"vpa":"neha@paysure",…}],…}

curl localhost:8080/api/accounts                         -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/accounts/$ACCOUNT_ID/balance     -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/accounts/$ACCOUNT_ID/ledger      -H "Authorization: Bearer $TOKEN"
curl -X POST localhost:8080/api/accounts/$ACCOUNT_ID/vpas -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"handle":"neha.rao"}'
curl -X POST localhost:8080/api/accounts/$ACCOUNT_ID/deactivate -H "Authorization: Bearer $TOKEN"
curl -X POST localhost:8080/api/accounts/$ACCOUNT_ID/activate   -H "Authorization: Bearer $TOKEN"

# Verify a payee before paying (returns the registered name only)
curl localhost:8080/api/vpas/rahul@paysure/resolve -H "Authorization: Bearer $TOKEN"
# 200 {"vpa":"rahul@paysure","payeeName":"Rahul Verma","ifsc":"PSBK0000001"}
```

## Payments

```bash
KEY=$(uuidgen)   # one key per logical payment; REUSE it when retrying the same payment
curl -X POST localhost:8080/api/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"payerVpa":"ananya@paysure","payeeVpa":"rahul@paysure","amountPaise":25000,"note":"Dinner payment"}'
# 201 Idempotent-Replayed: false
# {"id":"…","status":"SUCCESS","statusMessage":"Payment successful.","direction":"SENT","holdStatus":"CAPTURED",
#  "amountPaise":25000,"bankReference":"PSB…",…}

# Same key + same body again  -> 200, Idempotent-Replayed: true, same id, nothing charged again
# Same key + different body   -> 409 IDEMPOTENCY_KEY_REUSED

curl "localhost:8080/api/payments?status=PENDING" -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/payments/$PAYMENT_ID -H "Authorization: Bearer $TOKEN"
# {"payment":{…},"history":[{"toStatus":"CREATED",…},{"toStatus":"PROCESSING",…},…],
#  "attempts":[{"bankReference":"PSB…","responseKind":"TIMEOUT","callbacksReceived":0,…}],
#  "simulatedBank":{"committedStatus":"SUCCESS","responseDelivered":"TIMEOUT",…}}

# Ask the bank what really happened (never re-sends the transfer)
curl -X POST localhost:8080/api/payments/$PAYMENT_ID/check-status -H "Authorization: Bearer $TOKEN"
# {"effect":"SETTLED_SUCCESS","bankStatus":{"status":"SUCCESS",…},"payment":{"status":"SUCCESS",…}}
```

Fault injection (ADMIN_DEMO only) — add a `simulation` object:

```json
{"payerVpa":"labadmin@paysure","payeeVpa":"rahul@paysure","amountPaise":50000,
 "simulation":{"scenario":"TIMEOUT_AFTER_COMMIT"}}
```

```json
{"payerVpa":"labadmin@paysure","payeeVpa":"rahul@paysure","amountPaise":50000,
 "simulation":{"scenario":"DUPLICATE_CALLBACK","delaySeconds":5,"duplicateCallbacks":4}}
```

## Collect requests

```bash
curl -X POST localhost:8080/api/collect-requests -H "Authorization: Bearer $RAHUL" -H 'Content-Type: application/json' \
  -d '{"requesterVpa":"rahul@paysure","payerVpa":"ananya@paysure","amountPaise":40000,"note":"Movie tickets"}'
curl "localhost:8080/api/collect-requests?direction=incoming" -H "Authorization: Bearer $ANANYA"
curl -X POST localhost:8080/api/collect-requests/$ID/approve -H "Authorization: Bearer $ANANYA"   # pays; idempotent
curl -X POST localhost:8080/api/collect-requests/$ID/decline -H "Authorization: Bearer $ANANYA"
curl -X POST localhost:8080/api/collect-requests/$ID/cancel  -H "Authorization: Bearer $RAHUL"
```

## Sandbox dev tools (ADMIN_DEMO only, `PAYSURE_DEV_TOOLS_ENABLED=true`)

```bash
curl -X POST localhost:8080/api/dev/accounts/$ACCOUNT_ID/top-up -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"amountPaise":50000,"reason":"demo"}'

# Fast-forward the simulated bank (resolve delayed transfers, deliver due callbacks)
curl -X POST "localhost:8080/api/dev/bank/tick?skipAheadSeconds=600" -H "Authorization: Bearer $ADMIN_TOKEN"
# Seeded chaos: status / replay the same fault sequence
curl localhost:8080/api/dev/bank/chaos -H "Authorization: Bearer $ADMIN_TOKEN"
curl -X POST "localhost:8080/api/dev/bank/chaos/reset?seed=42" -H "Authorization: Bearer $ADMIN_TOKEN"
```

## Reconciliation, Lab and reliability score (ADMIN_DEMO only)

```bash
curl -X POST "localhost:8080/api/admin/reconciliation/run?minAgeSeconds=0" -H "Authorization: Bearer $ADMIN_TOKEN"
curl "localhost:8080/api/admin/reconciliation/report?hours=24"            -H "Authorization: Bearer $ADMIN_TOKEN"
curl localhost:8080/api/admin/reconciliation/manual-review                -H "Authorization: Bearer $ADMIN_TOKEN"

curl localhost:8080/api/admin/lab/scenarios -H "Authorization: Bearer $ADMIN_TOKEN"
curl -X POST localhost:8080/api/admin/lab/runs -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"scenario":"LOST_ACKNOWLEDGEMENT","amountPaise":50000,"payeeVpa":"rahul@paysure"}'
curl -X POST localhost:8080/api/admin/lab/runs/$RUN_ID/actions -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"action":"RECONCILE"}'     # or FAST_FORWARD, RESTORE_STATUS_API

curl "localhost:8080/api/admin/reliability?windowMinutes=60" -H "Authorization: Bearer $ADMIN_TOKEN"
# {"score":89,"components":[{"key":"success_rate","weight":30,"valueText":"100.0 % (4 of 4)","score":100,…},…,
#   {"key":"webhook_delivery","available":false,"valueText":"Not measurable yet: …"}],"disclaimer":"A configurable demo score…"}
```

## Webhooks, events and the cached status endpoint

```bash
# Register an endpoint (the secret is returned ONCE)
curl -X POST localhost:8080/api/webhooks/endpoints -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/hook","eventTypes":["payment.completed","payment.failed"]}'
# 201 {"endpoint":{"id":"…","enabled":true,"secretVersion":1,…},"secret":"whsec_…"}
# Blocked targets: 400 WEBHOOK_URL_BLOCKED (loopback, private, link-local/metadata, http, credentials, …)

curl localhost:8080/api/webhooks/endpoints                       -H "Authorization: Bearer $TOKEN"
curl -X POST localhost:8080/api/webhooks/endpoints/$ID/rotate-secret -H "Authorization: Bearer $TOKEN"
curl "localhost:8080/api/webhooks/deliveries?status=DEAD"         -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/webhooks/deliveries/$DELIVERY_ID           -H "Authorization: Bearer $TOKEN"   # attempts + payload
curl -X POST localhost:8080/api/webhooks/deliveries/$DELIVERY_ID/retry -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/webhooks/config                            -H "Authorization: Bearer $TOKEN"

# Cheap polling endpoint backed by Redis (X-Cache: HIT|MISS); strangers get 404
curl -i localhost:8080/api/payments/$PAYMENT_ID/status -H "Authorization: Bearer $TOKEN"

# Operator views (ADMIN_DEMO)
curl localhost:8080/api/admin/events/outbox    -H "Authorization: Bearer $ADMIN_TOKEN"
curl localhost:8080/api/admin/webhooks/receipts -H "Authorization: Bearer $ADMIN_TOKEN"
```

A rate-limited request gets `429` with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `{"code":"RATE_LIMITED",…}`.

## Risk (educational simulation) and monitoring

```bash
# What-if: how would this payment score right now? Nothing is recorded or sent. Only your own payer VPA.
curl -X POST localhost:8080/api/risk/assess -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"payerVpa":"ananya@paysure","payeeVpa":"teastall@paysure","amountPaise":6000000}'
# {"score":40,"level":"MEDIUM","action":"REVIEW","rules":[…],"explanation":["Risk score: 40/100 (medium).",
#  "Amount ₹60,000.00 is at or above the demo threshold of ₹50,000.00. (+25)","You have not completed a payment to this recipient before. (+15)"],
#  "suggestedAction":"…","wouldAlert":true,"wouldBlock":false,"mode":"ALERT","disclaimer":"Risk alerts are educational simulations…"}

curl "localhost:8080/api/risk/alerts?status=OPEN"            -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/risk/config                            -H "Authorization: Bearer $TOKEN"

# Demo admin
curl "localhost:8080/api/admin/risk/alerts?status=OPEN"        -H "Authorization: Bearer $ADMIN_TOKEN"
curl -X POST localhost:8080/api/admin/risk/alerts/$ID/review   -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"status":"ACKNOWLEDGED"}'
curl localhost:8080/api/admin/monitoring/summary               -H "Authorization: Bearer $ADMIN_TOKEN"
curl localhost:8080/api/admin/monitoring/series                -H "Authorization: Bearer $ADMIN_TOKEN"
curl localhost:8080/actuator/prometheus                        # scraped by Prometheus
```

In `ENFORCE` mode a payment at or above the block score returns `422 RISK_BLOCKED` with the explanation; in the default `ALERT` mode nothing is ever refused.

## Status codes

| Code | When |
|---|---|
| 400 `WEBHOOK_URL_BLOCKED` / `INVALID_EVENT_TYPE` / `VALIDATION_FAILED` / `INVALID_VPA_HANDLE` / `INVALID_VPA` / `INVALID_IDEMPOTENCY_KEY` / `INVALID_AMOUNT` | malformed input (field errors in `errors[]`); fractional paise like `250.5` are rejected, never truncated |
| 401 `UNAUTHENTICATED` / `INVALID_CREDENTIALS` | missing/expired/forged token; bad login (same message for unknown e-mail and wrong password) |
| 403 `FORBIDDEN` / `PAYER_NOT_OWNED` / `SIMULATION_NOT_ALLOWED` / `NOT_THE_PAYER` | wrong role, paying from someone else's VPA, non-admin fault injection |
| 404 `ACCOUNT_NOT_FOUND` / `VPA_NOT_FOUND` / `PAYEE_NOT_FOUND` / `PAYMENT_NOT_FOUND` | not found **or not yours** — ownership failures do not reveal existence |
| 409 `EMAIL_ALREADY_REGISTERED` / `VPA_TAKEN` / `IDEMPOTENCY_KEY_REUSED` | uniqueness conflicts |
| 429 `RATE_LIMITED` | per-user / per-IP limit exceeded (see `Retry-After`) |
| 422 `RISK_BLOCKED` (ENFORCE mode only) / `ALERT_ALREADY_REVIEWED` / `INVALID_REVIEW_STATUS` / `WEBHOOK_LIMIT_REACHED` / `DELIVERY_NOT_DEAD` / `ACCOUNT_LIMIT_REACHED` / `VPA_LIMIT_REACHED` / `ACCOUNT_INACTIVE` / `SELF_TRANSFER_NOT_ALLOWED` / `COLLECT_NOT_PENDING` / `COLLECT_EXPIRED` | business rules |

A payment refused for **insufficient funds** is still created (`201`, `status: FAILED`, `failureCode: INSUFFICIENT_FUNDS`) so it appears in history, like a declined UPI transaction.
