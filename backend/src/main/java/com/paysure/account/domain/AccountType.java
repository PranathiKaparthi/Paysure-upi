package com.paysure.account.domain;

/** CUSTOMER accounts belong to users; SYSTEM accounts (treasury, later: settlement) belong to the sandbox. */
public enum AccountType {
    CUSTOMER,
    SYSTEM
}
