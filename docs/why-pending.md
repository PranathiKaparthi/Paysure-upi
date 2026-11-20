# Why a Payment Can Be Pending Even When Money Was Debited

*A plain-language explanation of ambiguous outcomes, status verification and reconciliation — and how PaySure handles them.*

## The everyday version

You pay ₹500 at a shop. Your bank app spins, then says **"Pending"**. Your SMS says ₹500 was debited.
The shopkeeper says they haven't received anything. What happened?

Very often: **the money did move, but the confirmation got lost on the way back.**

A payment is a conversation between several systems — your app, the payment switch, your bank, the
shop's bank. Each step is a network call, and any network call can end in one of three ways:

| What the app hears | What it means |
|---|---|
| "Done" | The transfer happened. |
| "Declined" | The transfer definitely did **not** happen. |
| *Silence* (timeout, dropped connection) | **Unknown.** It may have happened or not. |

The third case is the hard one. Silence does not mean failure.

## Why the app must not guess

Suppose the app treats silence as failure and simply **tries again**:

1. Attempt 1 actually succeeded at the bank, but the reply was lost.
2. The app sends attempt 2. It also succeeds.
3. You have now paid ₹1,000 for a ₹500 coffee.

Suppose instead the app treats silence as success: if the bank had actually rejected it, the shop
thinks it was paid and you walk away with goods that were never paid for.

Both guesses are wrong some of the time. So a correct system **does not guess**. It says *Pending* and
goes to find out.

## How PaySure handles it

```mermaid
sequenceDiagram
    participant U as You
    participant P as PaySure
    participant B as (Simulated) Bank
    U->>P: Pay ₹500 (Idempotency-Key K)
    P->>P: Hold ₹500 (not spendable, not yet debited)
    P->>P: Save instruction with bank reference PSB…
    P->>B: Transfer PSB…
    B-->>B: Commits the transfer ✅
    B--xP: Reply lost (timeout)
    P->>U: PENDING — funds on hold, do not pay again
    Note over P,B: later (status check / callback / reconciliation)
    P->>B: What happened to PSB…?  (a question, not a new transfer)
    B->>P: SUCCESS
    P->>P: Post ledger once: debit you, credit shop; hold → captured
    P->>U: SUCCESS
```

1. **Funds are held before the bank is called.** Your available balance drops, but the money has not
   left the ledger. While the outcome is unknown, the same money cannot be spent twice.
2. **A durable instruction with a stable reference is saved first.** Even if PaySure crashes right
   after calling the bank, it can later ask "what happened to `PSB…`?".
3. **An unknown outcome becomes `PENDING`, never `FAILED`, and is never re-sent.**
4. **The truth is learned by asking, not by retrying** — through the bank's callback, a manual
   "Check status with bank", or the scheduled **reconciliation worker** ([details](reconciliation.md)), which
   leaves anything it still cannot confirm PENDING and escalates it to a human.
5. **The final result is applied exactly once.** The database allows only one ledger transfer per
   payment, and finished payments cannot change state. Duplicate or late messages are ignored.
6. **If the bank says it never received the request** (and enough time has passed), the payment is
   marked `FAILED` and the hold is released — nothing was debited.

## What about *your* retries?

If your app loses its connection to PaySure itself, it may not know whether the payment was created.
That's what the **Idempotency-Key** is for: the app resends the *same* request with the *same* key,
and PaySure returns the payment it already created instead of making a new one. PaySure's web UI does
exactly this with its **Retry safely** button.

## Try it in the sandbox

As the demo admin (`labadmin@example.com`), open the **Reliability Lab**, or send a payment with the **Lost acknowledgement**
(`TIMEOUT_AFTER_COMMIT`) scenario:

- The payment shows **PENDING**, with ₹500 *held*.
- The transaction page shows the simulated bank's own record: **actually committed: SUCCESS**,
  **told PaySure: TIMEOUT**.
- Click **Check status with bank** → **SUCCESS**. The ledger shows exactly one debit and one credit.
  Clicking again changes nothing.

## Honest limits

PaySure is a simulator. Real systems add settlement files, dispute processes, regulatory turnaround
times and manual operations for cases no automated check can settle. The principles — hold, record,
verify, apply once, never blindly retry — are the same.
