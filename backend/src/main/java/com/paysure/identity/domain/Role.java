package com.paysure.identity.domain;

/**
 * USER: an ordinary sandbox participant.
 * ADMIN_DEMO: a demo operator who may use sandbox tooling (top-ups, fault injection, reconciliation views).
 * It is not a real banking administrator role.
 */
public enum Role {
    USER,
    ADMIN_DEMO
}
