package com.paysure.payment.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single, central definition of allowed payment transitions.
 *
 * <pre>
 *   CREATED ──► PROCESSING ──► SUCCESS
 *      │            │  ▲
 *      │            ▼  │(never)
 *      │         PENDING ──► SUCCESS
 *      │            │
 *      └──► FAILED ◄┘ (also PROCESSING ──► FAILED)
 * </pre>
 *
 * Terminal states have no outgoing transitions, so SUCCESS → FAILED (or any regression such as
 * SUCCESS → PENDING caused by a late or out-of-order event) is impossible.
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(PaymentStatus.CREATED, EnumSet.of(PaymentStatus.PROCESSING, PaymentStatus.FAILED));
        ALLOWED.put(PaymentStatus.PROCESSING, EnumSet.of(PaymentStatus.PENDING, PaymentStatus.SUCCESS, PaymentStatus.FAILED));
        ALLOWED.put(PaymentStatus.PENDING, EnumSet.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED));
        ALLOWED.put(PaymentStatus.SUCCESS, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(PaymentStatus.FAILED, EnumSet.noneOf(PaymentStatus.class));
    }

    private PaymentStateMachine() {
    }

    public static boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void requireTransition(PaymentStatus from, PaymentStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalPaymentTransitionException(from, to);
        }
    }

    public static Set<PaymentStatus> allowedFrom(PaymentStatus from) {
        return Set.copyOf(ALLOWED.get(from));
    }
}
