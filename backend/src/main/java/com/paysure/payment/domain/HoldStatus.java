package com.paysure.payment.domain;

/** Lifecycle of the payer's reserved funds for one payment. Each hold is captured or released exactly once. */
public enum HoldStatus {
    NONE,
    HELD,
    CAPTURED,
    RELEASED
}
