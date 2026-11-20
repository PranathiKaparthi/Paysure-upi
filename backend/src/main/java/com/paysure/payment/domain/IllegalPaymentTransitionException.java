package com.paysure.payment.domain;

/** A programming/consistency error: callers must check {@link PaymentStateMachine#canTransition} first. */
public class IllegalPaymentTransitionException extends IllegalStateException {

    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalPaymentTransitionException(PaymentStatus from, PaymentStatus to) {
        super("illegal payment transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public PaymentStatus from() {
        return from;
    }

    public PaymentStatus to() {
        return to;
    }
}
