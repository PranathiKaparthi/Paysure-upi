# Risk engine (Phase 5)

> **Educational simulation.** The risk engine is a small set of transparent, deterministic rules. It is **not** a real fraud determination and
> there is **no machine-learning model** in PaySure. Every point of every score is explained to the user.

## How a score is built

`RiskEngine.evaluate(facts, properties)` is a pure function. Each rule adds a fixed, configurable number of points when its condition
holds; the score is the sum, capped at 100.

| Rule | Fires when | Default points |
|---|---|---|
| `HIGH_VELOCITY` | ≥ 8 transfers by the payer in 60 s (this one included) | 35 |
| `RAPID_REPEAT` | ≥ 3 transfers to the same payee in 60 s | 20 |
| `MULTIPLE_FAILURES` | ≥ 3 of the payer's payments failed in 10 min | 20 |
| `LARGE_AMOUNT` | amount ≥ ₹50,000 (demo threshold) | 25 |
| `NEW_RECIPIENT` | the payer never completed a payment to this payee | 15 |
| `NEW_RECIPIENT_BURST` | new recipient **and** ≥ 3 transfers in 5 min | 20 |

| Score | Level | Suggested action |
|---|---|---|
| < 40 | LOW | **ALLOW** |
| 40–69 | MEDIUM | **REVIEW** — allow but flag |
| ≥ 70 | HIGH | **REVIEW** — hold for manual review |
| ≥ 90 | HIGH | **BLOCK** suggested (acted on only in ENFORCE mode) |

All thresholds, windows, weights and score bands are configurable (`paysure.risk.*`). The facts come from PostgreSQL (`payments` history), so the
result is reproducible and testable.

**Worked example** (from the specification): 9 transfers in 60 s + a new recipient + an amount above the threshold →

```
Risk score: 75/100 (high).
9 transfers initiated in 60 seconds (threshold 8). (+35)
Amount ₹60,000.00 is at or above the demo threshold of ₹50,000.00. (+25)
You have not completed a payment to this recipient before. (+15)
```

(a unit test asserts exactly this text).

## Where it runs

Inside `PaymentIntake`, **after** the idempotency key has been claimed and **before** funds are reserved. That placement matters:

- ten concurrent duplicates of one request are evaluated and alerted **once** (the other nine become idempotent replays and never reach the risk check);
- it runs in the payment's own transaction, so the alert, the `risk.alert` outbox event and the payment commit together.

### Modes

| Mode | Behaviour |
|---|---|
| `ALERT` (default) | Record alerts and publish events. **Never refuses a payment.** |
| `ENFORCE` | A payment scoring ≥ `block-score` is refused with `422 RISK_BLOCKED` and the explanation. The claim rolls back (nothing is created, the Idempotency-Key is not consumed), but the **refusal itself is recorded in a separate transaction** so the evidence survives, and a `risk.alert` event with `blocked: true` is published. |

ALERT is the default so the demo (and bursts such as the Reliability Lab's retry storm) cannot unexpectedly fail.

## Alerts, review and events

- Alerts at or above the alert score are stored in `risk_alerts` with the rules that fired, the full explanation and the suggested action.
- `GET /api/risk/alerts` — your own alerts (`?status=`, `?paymentId=`). `POST /api/risk/assess` — a **what-if**: how would this payment score right now? Nothing is recorded or sent;
  only your own payer VPA is accepted. `GET /api/risk/config` — active thresholds.
- Demo admins: `GET /api/admin/risk/alerts`, `POST /api/admin/risk/alerts/{id}/review` (`ACKNOWLEDGED` / `DISMISSED`; an alert is reviewed once).
- `risk.alert` events travel through the transactional outbox → Kafka → the notification consumer, and are delivered by webhook to the **affected user's** endpoints
  (and demo admins') via `data.userId`. See [events.md](events.md) and [webhooks.md](webhooks.md).
- Metrics: `risk_evaluations_total{level}`, `risk_alerts_total{level}`, `risk_blocked_total` (bounded labels).

UI: **Risk Alerts** page — your alerts with explanations, a simulator, the rules and weights, and (demo admins) all users' alerts with Acknowledge/Dismiss.

## Honest limits

- Rules look only at the payer's PaySure history; there is no device, geography, network or behavioural data.
- Fixed thresholds can be gamed and will misfire on legitimate bursts; that is why the default mode never blocks.
- No model, no training data, no claim of detection accuracy.
