package com.paysure.bank.simulator;

import com.paysure.bank.SimulationScenario;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * Reproducible "chaos": with seeded chaos enabled, requests that do not ask for a specific scenario
 * draw one from a {@link Random} seeded with a fixed value. Resetting with the same seed replays the
 * same sequence of faults, which makes demonstrations repeatable.
 */
@Component
public class SeededFaultPlanner {

    static final List<SimulationScenario> FAULTS = List.of(
            SimulationScenario.BANK_TIMEOUT,
            SimulationScenario.TIMEOUT_AFTER_COMMIT,
            SimulationScenario.DELAYED_SUCCESS,
            SimulationScenario.DUPLICATE_CALLBACK,
            SimulationScenario.INSUFFICIENT_FUNDS);

    private final BankSimulatorProperties props;
    private Random random;
    private long seed;

    public SeededFaultPlanner(BankSimulatorProperties props) {
        this.props = props;
        reset(props.seededChaos().seed());
    }

    public synchronized SimulationScenario choose(SimulationScenario requested) {
        if (requested != null) {
            return requested;
        }
        if (!props.seededChaos().enabled()) {
            return props.defaultScenario();
        }
        if (random.nextDouble() < props.seededChaos().faultRate()) {
            return FAULTS.get(random.nextInt(FAULTS.size()));
        }
        return SimulationScenario.NORMAL_SUCCESS;
    }

    public synchronized void reset(long newSeed) {
        this.seed = newSeed;
        this.random = new Random(newSeed);
    }

    public synchronized long seed() {
        return seed;
    }

    public boolean enabled() {
        return props.seededChaos().enabled();
    }
}
