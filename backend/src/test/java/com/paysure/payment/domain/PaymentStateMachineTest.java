package com.paysure.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentStateMachineTest {

    @ParameterizedTest
    @CsvSource({"CREATED,PROCESSING", "CREATED,FAILED", "PROCESSING,PENDING", "PROCESSING,SUCCESS",
            "PROCESSING,FAILED", "PENDING,SUCCESS", "PENDING,FAILED"})
    void allowedTransitions(PaymentStatus from, PaymentStatus to) {
        assertThat(PaymentStateMachine.canTransition(from, to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,FAILED", "FAILED,SUCCESS", "SUCCESS,PENDING", "SUCCESS,PROCESSING", "FAILED,PENDING",
            "PENDING,PROCESSING", "PENDING,CREATED", "CREATED,SUCCESS", "CREATED,PENDING", "PROCESSING,CREATED"})
    void forbiddenTransitions(PaymentStatus from, PaymentStatus to) {
        assertThat(PaymentStateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> PaymentStateMachine.requireTransition(from, to))
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCESS", "FAILED"})
    void terminalStatesHaveNoExits(PaymentStatus terminal) {
        assertThat(PaymentStateMachine.allowedFrom(terminal)).isEmpty();
        assertThat(terminal.isTerminal()).isTrue();
    }

    @Test
    void paymentEnforcesHoldConsistency() {
        Payment p = Payment.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a@paysure",
                UUID.randomUUID(), UUID.randomUUID(), "b@paysure", 100, null, null);
        Instant now = Instant.now();
        // Cannot submit without holding funds.
        assertThatThrownBy(() -> p.transition(PaymentStatus.PROCESSING, TransitionSource.API, "x", now))
                .isInstanceOf(IllegalStateException.class);
        p.markFundsHeld();
        p.transition(PaymentStatus.PROCESSING, TransitionSource.API, "x", now);
        // Cannot succeed without capturing, nor fail while still holding.
        assertThatThrownBy(() -> p.transition(PaymentStatus.SUCCESS, TransitionSource.API, "x", now))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.transition(PaymentStatus.FAILED, TransitionSource.API, "x", now))
                .isInstanceOf(IllegalStateException.class);
        p.markHoldCaptured();
        PaymentStateChange change = p.transition(PaymentStatus.SUCCESS, TransitionSource.BANK_RESPONSE, "ok", now);
        assertThat(change.getFromStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(p.getCompletedAt()).isEqualTo(now);
        // Terminal: SUCCESS -> FAILED is impossible.
        assertThatThrownBy(() -> p.transition(PaymentStatus.FAILED, TransitionSource.BANK_CALLBACK, "late", now))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(p::markHoldReleased).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void attemptIgnoresDuplicateAndStaleCallbacks() {
        PaymentAttempt a = new PaymentAttempt(UUID.randomUUID(), 1, "PSB1", null, 0, 1);
        assertThat(a.acceptCallback(2)).isTrue();
        assertThat(a.acceptCallback(2)).isFalse();
        assertThat(a.acceptCallback(1)).isFalse();
        assertThat(a.acceptCallback(3)).isTrue();
        assertThat(a.getCallbacksReceived()).isEqualTo(2);
        assertThat(a.getCallbacksIgnored()).isEqualTo(2);
    }
}
