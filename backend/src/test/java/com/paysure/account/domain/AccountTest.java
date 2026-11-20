package com.paysure.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paysure.common.error.BusinessRuleException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

    private final Account account = Account.openCustomerAccount(UUID.randomUUID(), "912345678901", "Savings");

    @Test
    void creditsAndDebitsInPaise() {
        account.credit(25_050);
        account.debit(50);
        assertThat(account.getBalancePaise()).isEqualTo(25_000);
        assertThat(account.availablePaise()).isEqualTo(25_000);
    }

    @Test
    void customerCannotGoBelowZero() {
        account.credit(100);
        assertThatThrownBy(() -> account.debit(101))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient");
        assertThat(account.getBalancePaise()).isEqualTo(100);
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> account.credit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debit(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inactiveAccountCannotMoveMoney() {
        account.credit(100);
        account.deactivate();
        assertThatThrownBy(() -> account.debit(1)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> account.credit(1)).isInstanceOf(BusinessRuleException.class);
        account.activate();
        account.debit(1);
        assertThat(account.getBalancePaise()).isEqualTo(99);
    }

    @Test
    void overflowIsDetectedNotWrapped() {
        account.credit(Long.MAX_VALUE);
        assertThatThrownBy(() -> account.credit(1)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void masksAccountNumber() {
        assertThat(account.getMaskedAccountNumber()).isEqualTo("XXXXXXXX8901");
    }
}
