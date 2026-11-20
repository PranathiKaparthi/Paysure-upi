# Webhooks (Phase 4)

PaySure notifies your server about payment events. Deliveries are **durable**, **signed**, **retried**, **dead-lettered** when they
keep failing, and **safe to receive more than once**. A webhook only *reports* what already happened; a retry can never move money.

## Registering an endpoint

`POST /api/webhooks/endpoints` `{ "url": "https://…", "eventTypes": ["payment.completed"], "description": "…" }`
(UI: **Webhooks** page). Up to 5 endpoints per user; omit `eventTypes` (or use `*`) for all events.

- You receive events for payments you are the **payer or payee** of. Demo admins receive every event; operational events
  (`reconciliation.completed`) go to demo admins only.
- The response contains `secret` **once**. Secrets are *derived* (`HMAC(server key, endpointId:version)`), never stored, so a database
  dump does not reveal them. `POST …/rotate-secret` issues a new one and invalidates the old one at once.

## What a delivery looks like

```http
POST /your/path HTTP/1.1
Content-Type: application/json
X-PaySure-Event-Id: 6f6c…          # stable across every retry → deduplicate on this
X-PaySure-Event-Type: payment.completed
X-PaySure-Delivery-Id: 9a61…
X-PaySure-Endpoint-Id: de3e…
X-PaySure-Timestamp: 1795194335    # seconds; part of the signed message
X-PaySure-Signature: v1=4f1c…

{"eventId":"6f6c…","eventType":"payment.completed","occurredAt":"2026-11-20T10:00:00Z","aggregateType":"PAYMENT",
 "aggregateId":"…","data":{"paymentId":"…","status":"SUCCESS","amountPaise":25000,"currency":"INR","payerVpa":"…",
 "payeeVpa":"…","bankReference":"PSB…","resolvedBy":"RECONCILIATION"}}
```

### Verifying (receiver checklist)

1. Read the **raw** body bytes (do not re-serialise JSON).
2. `expected = "v1=" + hex( HMAC_SHA256( secret, timestamp + "." + rawBody ) )`; compare in **constant time**.
3. Reject if `|now − timestamp| > 5 minutes` (**replay window**).
4. Deduplicate by `X-PaySure-Event-Id` and make your effect idempotent; answer **2xx** for a duplicate so the sender stops retrying.
5. Answer any non-2xx (or time out) to ask for a retry.

Why timestamp + body: the MAC covers the exact bytes, so any tampering breaks it; the timestamp is inside the MAC and bounded by the
window, so a captured request cannot be replayed later. Replays inside the window are absorbed by step 4.

## Delivery semantics

| | |
|---|---|
| Guarantee | at-least-once; same event id and body on every attempt |
| Retries | up to 6 attempts; delay = **equal-jitter exponential backoff**: ceiling `min(5 min, 5 s × 2^(n−1))`, actual delay uniformly in `[ceiling/2, ceiling]` (bounded, never 0, spreads retries) |
| Failure = | non-2xx, timeout (5 s), connection error. Redirects are **never** followed |
| After the last attempt | status `DEAD` (dead-letter): kept with its attempt log; the owner can **Retry** it (`POST …/deliveries/{id}/retry`; same event id) |
| States | `PENDING → DELIVERING → SUCCESS` or `→ RETRYING → … → DEAD` |
| Records | `webhook_deliveries` + `webhook_delivery_attempts` (status code, duration, error per attempt), shown in the UI |
| Concurrency | claimed with `FOR UPDATE SKIP LOCKED` + a lease; a crashed worker's lease expires |

## SSRF protection

Registration **and every delivery** (the DNS answer can change after registration) are validated by `SsrfGuard`:

- only `http(s)`, **https required**, no credentials in the URL, no fragment, port 1–65535;
- the host must resolve and **every** resolved address must be public — loopback, RFC 1918 private, link-local (including the cloud
  metadata address `169.254.169.254`), carrier-grade NAT `100.64.0.0/10`, multicast, reserved/broadcast, benchmarking/documentation
  ranges, IPv6 loopback / unique-local / link-local / NAT64 are refused (IPv4-mapped IPv6 and odd forms like `2130706433` or `127.1` resolve
  to their real address and are caught);
- a delivery whose target has become blocked is dead-lettered immediately, **without sending a request**;
- redirects are never followed.

**Local demo exception:** `paysure.webhooks.local-allowlist` (e.g. `localhost:8080`) names exact `host:port` pairs exempt from the
private-network and https rules, so the built-in sandbox receiver can be used. It is empty by default in production.

*Residual risk (documented, not hidden):* the JDK HTTP client resolves the name again when it connects, so a DNS-rebinding race between the
check and the connection is narrowed, not eliminated. A hardened deployment would pin the validated address or send webhooks via an egress proxy.

## The built-in sandbox receiver (local demonstration)

`POST /webhook-sandbox/receiver?fail=N` (enabled by `PAYSURE_WEBHOOK_SANDBOX_RECEIVER=true`; authenticated by the signature, not a JWT).
It does what a correct receiver does: verifies signature + timestamp (401 otherwise, nothing recorded), checks the event id header matches the
signed body, then records the call in `webhook_receipts`. The first `N` calls per event answer **500** on purpose; after that it **processes**
the event — setting `processed_at` **at most once per event** — and answers 200; further calls answer 200 "duplicate ignored".

Try it: **Webhooks** page → *Use sandbox receiver* (fail first 2) → *Register endpoint* → make a payment. Watch the delivery go
`RETRYING (HTTP 500) → RETRYING (HTTP 500) → SUCCESS`, with the operator panel showing *calls = 3, processed once ✓*.

## Metrics

`webhook_deliveries_total{outcome=success|retry|dead}`, `webhook_delivery_failures_total`, `webhook_deliveries_enqueued_total`,
`events_duplicates_ignored_total`. Labels are bounded (no endpoint, user or event ids).
