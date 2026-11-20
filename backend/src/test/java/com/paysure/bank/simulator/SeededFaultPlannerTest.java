package com.paysure.bank.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.paysure.bank.SimulationScenario;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeededFaultPlannerTest {

    private static BankSimulatorProperties props(boolean chaos) {
        return new BankSimulatorProperties(SimulationScenario.NORMAL_SUCCESS, 5, 0, false, 1000,
                new BankSimulatorProperties.SeededChaos(chaos, 42, 0.5));
    }

    @Test
    void sameSeedReplaysTheSameFaultSequence() {
        SeededFaultPlanner planner = new SeededFaultPlanner(props(true));
        List<SimulationScenario> first = draw(planner, 50);
        planner.reset(42);
        assertThat(draw(planner, 50)).isEqualTo(first);
        assertThat(first).contains(SimulationScenario.NORMAL_SUCCESS).hasSizeGreaterThan(1)
                .anyMatch(s -> s != SimulationScenario.NORMAL_SUCCESS);
        planner.reset(7);
        assertThat(draw(planner, 50)).isNotEqualTo(first);
    }

    @Test
    void explicitScenarioAlwaysWinsAndChaosOffUsesDefault() {
        SeededFaultPlanner chaos = new SeededFaultPlanner(props(true));
        assertThat(chaos.choose(SimulationScenario.BANK_UNAVAILABLE)).isEqualTo(SimulationScenario.BANK_UNAVAILABLE);
        SeededFaultPlanner calm = new SeededFaultPlanner(props(false));
        assertThat(draw(calm, 20)).containsOnly(SimulationScenario.NORMAL_SUCCESS);
    }

    private static List<SimulationScenario> draw(SeededFaultPlanner p, int n) {
        List<SimulationScenario> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(p.choose(null));
        }
        return out;
    }
}
