package com.paysure.ledger.domain;

/** From the account holder's perspective: CREDIT increases the balance, DEBIT decreases it. */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
