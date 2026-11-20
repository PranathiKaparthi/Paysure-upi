package com.paysure.payment.application;

import com.paysure.bank.BankContracts.SimulationOptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * A request to pay. {@code callerUserId} always comes from the verified JWT.
 *
 * @param simulation       sandbox fault injection (ADMIN_DEMO only); null = simulator default
 * @param collectRequestId set when the payment fulfils a collect request (internal use)
 */
public record PaymentCommand(UUID callerUserId, boolean callerIsAdminDemo, String idempotencyKey,
                             String payerVpa, String payeeVpa, long amountPaise, String note,
                             SimulationOptions simulation, UUID collectRequestId) {

    /**
     * SHA-256 over a canonical, versioned representation of everything that defines the payment.
     * Normalisation (case, whitespace) happens first, so "Rahul@PaySure" and "rahul@paysure" hash equally.
     */
    public String canonicalHash() {
        String canonical = String.join("|",
                "v1",
                norm(payerVpa),
                norm(payeeVpa),
                Long.toString(amountPaise),
                note == null ? "" : note.trim(),
                simulation == null || simulation.scenario() == null ? "" : simulation.scenario().name(),
                simulation == null ? "" : Integer.toString(simulation.delaySeconds()),
                simulation == null ? "" : Integer.toString(simulation.duplicateCallbacks()),
                collectRequestId == null ? "" : collectRequestId.toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String norm(String vpa) {
        return vpa == null ? "" : vpa.trim().toLowerCase(Locale.ROOT);
    }
}
