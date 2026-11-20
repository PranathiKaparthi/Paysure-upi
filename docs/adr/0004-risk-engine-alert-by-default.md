# ADR 0004 — A transparent rule-based risk engine that alerts by default and never blocks

**Status:** accepted (Phase 5)

## Context
The brief asks for a risk engine. Real fraud detection needs labelled data, real device and network signals and regulatory care; a
sandbox has none of that, and a wrong "block" on a payment is worse than a wrong alert.

## Decision
Six pure, explainable rules (high velocity, rapid repeats, multiple failures, large amount, new recipient, new-recipient burst) add
configurable points to a 0–100 score; the response lists every triggered rule, a plain-language explanation and a suggested action.
Two modes: **ALERT** (default) records an alert and a `risk.alert` event and *never* refuses a payment; **ENFORCE** refuses scores at or
above a threshold and records the refusal. Evaluation happens after the idempotency claim, inside the payment transaction, so alerts commit
or roll back with the payment. No machine learning. Every surface says it is an educational simulation, not a fraud determination.

## Consequences
- Behaviour is deterministic and unit-testable (`RiskEngineTest`) and demonstrable (`/api/risk/assess` what-if, the Risk Alerts page).
- Weights and thresholds are configuration, not learned; they will be wrong for real traffic and are not meant to be reused as such.
- ENFORCE exists mainly to show the integration point and is covered by `RiskEnforceTest`.
