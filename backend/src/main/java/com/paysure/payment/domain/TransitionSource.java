package com.paysure.payment.domain;

/** Who or what caused a status transition (recorded in the append-only history). */
public enum TransitionSource {
    API,
    BANK_RESPONSE,
    BANK_CALLBACK,
    STATUS_CHECK,
    RECONCILIATION
}
