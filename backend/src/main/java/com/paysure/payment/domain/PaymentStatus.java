package com.paysure.payment.domain;

public enum PaymentStatus {
    /** Intent recorded; funds not yet reserved. */
    CREATED,
    /** Funds reserved; durable instruction written; being submitted to the bank. */
    PROCESSING,
    /** Outcome unknown (ambiguous bank response or awaiting callback). Funds stay reserved. Never blindly retried. */
    PENDING,
    /** Bank confirmed. Ledger posted exactly once. Terminal. */
    SUCCESS,
    /** Definitively not executed. Hold released (if any). Terminal. */
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
