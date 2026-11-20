package com.paysure.bank.simulator;

import com.paysure.bank.BankCallbackListener;
import com.paysure.bank.BankContracts.BankSideCallback;
import com.paysure.bank.BankContracts.BankSideView;
import com.paysure.bank.BankContracts.Callback;
import com.paysure.bank.BankContracts.ResponseKind;
import com.paysure.bank.BankContracts.StatusResult;
import com.paysure.bank.BankContracts.SubmitResult;
import com.paysure.bank.BankContracts.TransferInstruction;
import com.paysure.bank.BankContracts.TransferStatus;
import com.paysure.bank.BankGateway;
import com.paysure.bank.BankInspector;
import com.paysure.bank.SimulationScenario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deterministic in-process bank simulator. It behaves like a separate system: its own tables, its own
 * transactions ({@code REQUIRES_NEW}), and it talks to PaySure only through {@link BankGateway} and
 * {@link BankCallbackListener}.
 *
 * <p>Key property: every transfer is keyed by the caller's stable {@code bankReference}. Submitting a
 * reference again never commits a second transfer, and {@link #queryStatus} never initiates one.
 */
@Service
public class SimulatedBank implements BankGateway, BankInspector {

    private static final Logger log = LoggerFactory.getLogger(SimulatedBank.class);

    private final SimTransferRepository transfers;
    private final SimCallbackRepository callbacks;
    private final SeededFaultPlanner planner;
    private final BankSimulatorProperties props;
    private final ObjectProvider<BankCallbackListener> listener;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SimulatedBank(SimTransferRepository transfers, SimCallbackRepository callbacks, SeededFaultPlanner planner,
                         BankSimulatorProperties props, ObjectProvider<BankCallbackListener> listener,
                         PlatformTransactionManager txManager, Clock clock) {
        this.transfers = transfers;
        this.callbacks = callbacks;
        this.planner = planner;
        this.props = props;
        this.listener = listener;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Override
    public SubmitResult submit(TransferInstruction instruction) {
        simulateLatency();
        SimulationScenario scenario = planner.choose(instruction.simulation().scenario());
        try {
            return tx.execute(status -> doSubmit(instruction, scenario));
        } catch (DataIntegrityViolationException concurrentSameReference) {
            // Another submit with the same reference committed first: report its outcome, never commit twice.
            return tx.execute(status -> replay(transfers.findById(instruction.bankReference()).orElseThrow()));
        }
    }

    private SubmitResult doSubmit(TransferInstruction in, SimulationScenario scenario) {
        var existing = transfers.findById(in.bankReference());
        if (existing.isPresent()) {
            log.info("Bank: duplicate submit for {} — returning original outcome, no new transfer", in.bankReference());
            return replay(existing.get());
        }
        Instant now = clock.instant();
        int delay = in.simulation().delaySeconds() > 0 ? in.simulation().delaySeconds() : props.defaultDelaySeconds();
        Instant resolvesAt = now.plusSeconds(delay);

        SubmitResult result = switch (scenario) {
            case NETWORK_FAILURE -> new SubmitResult(ResponseKind.NETWORK_ERROR, "Connection reset before the bank received the request");
            case BANK_UNAVAILABLE -> new SubmitResult(ResponseKind.UNAVAILABLE, "Bank temporarily unavailable; request not accepted");
            case NORMAL_SUCCESS -> record(in, scenario, TransferStatus.SUCCESS, null, ResponseKind.SUCCESS, null, now);
            case INSUFFICIENT_FUNDS -> record(in, scenario, TransferStatus.FAILED, "Insufficient funds at remitter bank",
                    ResponseKind.DECLINED, null, now);
            case BANK_TIMEOUT -> record(in, scenario, TransferStatus.FAILED, "Timed out before debit; not processed",
                    ResponseKind.TIMEOUT, null, now);
            case TIMEOUT_AFTER_COMMIT -> record(in, scenario, TransferStatus.SUCCESS, null, ResponseKind.TIMEOUT, null, now);
            case DELAYED_SUCCESS, DELAYED_FAILURE, DUPLICATE_CALLBACK, OUT_OF_ORDER_CALLBACK -> {
                SubmitResult accepted = record(in, scenario, TransferStatus.PROCESSING, null, ResponseKind.ACCEPTED, resolvesAt, now);
                scheduleCallbacks(in, scenario, resolvesAt, now);
                yield accepted;
            }
        };
        log.info("Bank: {} for {} -> response {}", scenario, in.bankReference(), result.kind());
        return result;
    }

    private SubmitResult record(TransferInstruction in, SimulationScenario scenario, TransferStatus committed,
                                String reason, ResponseKind response, Instant resolvesAt, Instant now) {
        transfers.saveAndFlush(new SimTransfer(in.bankReference(), in.payerAccountId(), in.payeeAccountId(),
                in.amountPaise(), scenario, committed, reason, response, resolvesAt, now));
        String message = switch (response) {
            case SUCCESS -> "Transfer committed";
            case DECLINED -> reason;
            case ACCEPTED -> "Accepted for processing; final status will follow";
            case TIMEOUT -> "No response from bank within the timeout";
            default -> reason;
        };
        return new SubmitResult(response, message);
    }

    private void scheduleCallbacks(TransferInstruction in, SimulationScenario scenario, Instant resolvesAt, Instant now) {
        List<SimCallback> planned = new ArrayList<>();
        switch (scenario) {
            case DELAYED_SUCCESS -> planned.add(new SimCallback(in.bankReference(), 1, TransferStatus.SUCCESS, resolvesAt, now));
            case DELAYED_FAILURE -> planned.add(new SimCallback(in.bankReference(), 1, TransferStatus.FAILED, resolvesAt, now));
            case DUPLICATE_CALLBACK -> {
                for (int i = 0; i < in.simulation().duplicateCallbacks(); i++) {
                    planned.add(new SimCallback(in.bankReference(), 1, TransferStatus.SUCCESS, resolvesAt.plusMillis(i), now));
                }
            }
            case OUT_OF_ORDER_CALLBACK -> {
                // The final status (seq 2) is delivered BEFORE the stale intermediate one (seq 1).
                planned.add(new SimCallback(in.bankReference(), 2, TransferStatus.SUCCESS, resolvesAt, now));
                planned.add(new SimCallback(in.bankReference(), 1, TransferStatus.PROCESSING, resolvesAt.plusSeconds(1), now));
            }
            default -> {
            }
        }
        callbacks.saveAll(planned);
    }

    private static SubmitResult replay(SimTransfer t) {
        return switch (t.getCommittedStatus()) {
            case SUCCESS -> new SubmitResult(ResponseKind.SUCCESS, "Already committed");
            case FAILED -> new SubmitResult(ResponseKind.DECLINED, t.getFailureReason());
            default -> new SubmitResult(ResponseKind.ACCEPTED, "Already accepted; processing");
        };
    }

    /** Sandbox switch for the Reliability Lab: when false, status enquiries fail as if the bank's API were down. */
    private final java.util.concurrent.atomic.AtomicBoolean statusApiAvailable = new java.util.concurrent.atomic.AtomicBoolean(true);

    public void setStatusApiAvailable(boolean available) {
        statusApiAvailable.set(available);
        log.info("Bank: status enquiry API is now {}", available ? "UP" : "DOWN");
    }

    public boolean isStatusApiAvailable() {
        return statusApiAvailable.get();
    }

    @Override
    public StatusResult queryStatus(String bankReference) {
        if (!statusApiAvailable.get()) {
            throw new com.paysure.bank.BankContracts.BankUnreachableException("Bank status enquiry API unavailable (simulated outage)");
        }
        Instant now = clock.instant();
        return tx.execute(status -> transfers.findById(bankReference)
                .map(t -> {
                    t.resolveIfDue(now);
                    return new StatusResult(bankReference, t.getCommittedStatus(), t.getFailureReason());
                })
                .orElse(new StatusResult(bankReference, TransferStatus.NOT_FOUND, "No record of this reference")));
    }

    /**
     * Advances the simulator: resolves due delayed transfers, then delivers due callbacks (at-least-once;
     * a failed delivery is retried with exponential backoff). Synchronised so the background scheduler and
     * manual demo triggers do not interleave.
     */
    public synchronized TickResult tick(Instant now) {
        Integer resolved = tx.execute(status -> {
            int n = 0;
            for (SimTransfer t : transfers.findDue(now)) {
                if (t.resolveIfDue(now)) {
                    n++;
                }
            }
            return n;
        });
        List<SimCallback> due = tx.execute(status -> callbacks.findDue(now, PageRequest.of(0, 100)));
        int delivered = 0;
        int failed = 0;
        BankCallbackListener target = listener.getIfAvailable();
        for (SimCallback cb : due == null ? List.<SimCallback>of() : due) {
            String reason = transfers.findById(cb.getBankReference()).map(SimTransfer::getFailureReason).orElse(null);
            try {
                if (target == null) {
                    throw new IllegalStateException("no callback listener registered");
                }
                target.onCallback(new Callback(cb.getId(), cb.getBankReference(), cb.getSequence(), cb.getReportedStatus(), reason));
                tx.executeWithoutResult(s -> callbacks.findById(cb.getId()).ifPresent(c -> c.markDelivered(now)));
                delivered++;
            } catch (RuntimeException e) {
                log.warn("Bank: callback {} for {} failed (attempt {}): {}", cb.getId(), cb.getBankReference(),
                        cb.getDeliveryAttempts() + 1, e.getMessage());
                long backoff = Math.min(60, 1L << Math.min(cb.getDeliveryAttempts(), 6));
                tx.executeWithoutResult(s -> callbacks.findById(cb.getId())
                        .ifPresent(c -> c.markFailed(now.plus(Duration.ofSeconds(backoff)), e.getMessage())));
                failed++;
            }
        }
        return new TickResult(resolved == null ? 0 : resolved, delivered, failed);
    }

    /** Read-only view of the bank's side of a transfer, for the Reliability Lab and transaction details. */
    @Override
    public java.util.Optional<BankSideView> inspect(String bankReference) {
        return tx.execute(status -> transfers.findById(bankReference).map(t -> new BankSideView(
                t.getBankReference(), t.getScenario(), t.getCommittedStatus(), t.getResponseKind(), t.getFailureReason(),
                t.getAmountPaise(), t.getResolvesAt(), t.getCommittedAt(), t.getCreatedAt(),
                callbacks.findByBankReferenceOrderByDeliverAtAsc(bankReference).stream()
                        .map(c -> new BankSideCallback(c.getId(), c.getSequence(), c.getReportedStatus(), c.getDeliverAt(),
                                c.getDeliveredAt(), c.getDeliveryAttempts()))
                        .toList())));
    }

    private void simulateLatency() {
        if (props.latencyMs() > 0) {
            try {
                Thread.sleep(props.latencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public record TickResult(int transfersResolved, int callbacksDelivered, int callbacksFailed) {
    }

}
