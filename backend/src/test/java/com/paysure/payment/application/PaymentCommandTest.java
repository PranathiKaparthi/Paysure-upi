package com.paysure.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.paysure.bank.BankContracts.SimulationOptions;
import com.paysure.bank.SimulationScenario;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentCommandTest {

    private static final UUID CALLER = UUID.randomUUID();

    private static PaymentCommand cmd(String payer, String payee, long amount, String note, SimulationOptions sim) {
        return new PaymentCommand(CALLER, false, "key-123456", payer, payee, amount, note, sim, null);
    }

    @Test
    void hashIgnoresCaseAndWhitespaceInVpas() {
        assertThat(cmd(" Ananya@PaySure", "RAHUL@paysure ", 100, "Dinner", null).canonicalHash())
                .isEqualTo(cmd("ananya@paysure", "rahul@paysure", 100, " Dinner ", null).canonicalHash())
                .hasSize(64);
    }

    @Test
    void hashChangesWithAnyBusinessField() {
        String base = cmd("a@paysure", "b@paysure", 100, null, null).canonicalHash();
        assertThat(cmd("a@paysure", "b@paysure", 101, null, null).canonicalHash()).isNotEqualTo(base);
        assertThat(cmd("a@paysure", "c@paysure", 100, null, null).canonicalHash()).isNotEqualTo(base);
        assertThat(cmd("a@paysure", "b@paysure", 100, "x", null).canonicalHash()).isNotEqualTo(base);
        assertThat(cmd("a@paysure", "b@paysure", 100, null,
                new SimulationOptions(SimulationScenario.BANK_TIMEOUT, 0, 1)).canonicalHash()).isNotEqualTo(base);
    }
}
