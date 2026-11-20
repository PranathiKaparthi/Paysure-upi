package com.paysure.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transparent, deterministic, rule-based risk scoring. There is NO machine-learning model here: each rule adds a fixed,
 * configurable number of points when its condition holds, the score is the capped sum, and every point is explained.
 *
 * <p>This is an educational simulation of the kind of velocity/anomaly checks a payment platform runs. It is not a real
 * fraud determination and must not be presented as one.
 *
 * <table>
 *   <tr><th>Rule</th><th>Fires when</th></tr>
 *   <tr><td>HIGH_VELOCITY</td><td>≥ N transfers by the payer in the velocity window (this one included)</td></tr>
 *   <tr><td>RAPID_REPEAT</td><td>≥ N transfers to the same payee in the repeat window</td></tr>
 *   <tr><td>MULTIPLE_FAILURES</td><td>≥ N of the payer's payments failed in the failure window</td></tr>
 *   <tr><td>LARGE_AMOUNT</td><td>amount ≥ the configured sandbox threshold</td></tr>
 *   <tr><td>NEW_RECIPIENT</td><td>the payer has never completed a payment to this payee</td></tr>
 *   <tr><td>NEW_RECIPIENT_BURST</td><td>new recipient AND ≥ N transfers in the burst window</td></tr>
 * </table>
 */
public final class RiskEngine {

    public enum Level { LOW, MEDIUM, HIGH }

    public enum Action { ALLOW, REVIEW, BLOCK }

    /**
     * What the engine knows about the payment being evaluated. Counts INCLUDE the payment itself.
     */
    public record Facts(long amountPaise, int transfersInVelocityWindow, int transfersToSamePayeeInRepeatWindow,
                        int failedInFailureWindow, boolean newRecipient, int transfersInBurstWindow) {
    }

    public record RuleHit(String code, String label, int points, String detail) {
    }

    public record Assessment(int score, Level level, Action action, List<RuleHit> rules, List<String> explanation,
                             String suggestedAction, boolean alertWorthy) {
    }

    private RiskEngine() {
    }

    public static Assessment evaluate(Facts f, RiskProperties p) {
        List<RuleHit> hits = new ArrayList<>();
        if (f.transfersInVelocityWindow() >= p.velocityThreshold()) {
            hits.add(new RuleHit("HIGH_VELOCITY", "High transaction velocity", p.velocityPoints(),
                    f.transfersInVelocityWindow() + " transfers initiated in " + window(p.velocityWindowSeconds())
                            + " (threshold " + p.velocityThreshold() + ")."));
        }
        if (f.transfersToSamePayeeInRepeatWindow() >= p.repeatThreshold()) {
            hits.add(new RuleHit("RAPID_REPEAT", "Rapid repeat transfers", p.repeatPoints(),
                    f.transfersToSamePayeeInRepeatWindow() + " transfers to the same recipient in " + window(p.repeatWindowSeconds())
                            + " (threshold " + p.repeatThreshold() + ")."));
        }
        if (f.failedInFailureWindow() >= p.failureThreshold()) {
            hits.add(new RuleHit("MULTIPLE_FAILURES", "Multiple failed payments", p.failurePoints(),
                    f.failedInFailureWindow() + " payments failed in the last " + window(p.failureWindowSeconds())
                            + " (threshold " + p.failureThreshold() + ")."));
        }
        if (f.amountPaise() >= p.largeAmountPaise()) {
            hits.add(new RuleHit("LARGE_AMOUNT", "Unusually large sandbox transfer", p.largeAmountPoints(),
                    "Amount " + rupees(f.amountPaise()) + " is at or above the demo threshold of " + rupees(p.largeAmountPaise()) + "."));
        }
        if (f.newRecipient()) {
            hits.add(new RuleHit("NEW_RECIPIENT", "New recipient", p.newRecipientPoints(),
                    "You have not completed a payment to this recipient before."));
            if (f.transfersInBurstWindow() >= p.burstThreshold()) {
                hits.add(new RuleHit("NEW_RECIPIENT_BURST", "New recipient during a burst of activity", p.burstPoints(),
                        f.transfersInBurstWindow() + " transfers in " + window(p.burstWindowSeconds()) + " while paying a new recipient (threshold "
                                + p.burstThreshold() + ")."));
            }
        }

        int score = Math.min(100, hits.stream().mapToInt(RuleHit::points).sum());
        Level level = score >= p.highScore() ? Level.HIGH : score >= p.alertScore() ? Level.MEDIUM : Level.LOW;
        Action action = score >= p.blockScore() ? Action.BLOCK : score >= p.alertScore() ? Action.REVIEW : Action.ALLOW;

        List<String> explanation = new ArrayList<>();
        explanation.add("Risk score: " + score + "/100 (" + level.name().toLowerCase(Locale.ROOT) + ").");
        if (hits.isEmpty()) {
            explanation.add("No risk rule was triggered.");
        } else {
            hits.forEach(h -> explanation.add(h.detail() + " (+" + h.points() + ")"));
            int raw = hits.stream().mapToInt(RuleHit::points).sum();
            if (raw > 100) {
                explanation.add("Points add up to " + raw + " and are capped at 100.");
            }
        }
        String suggested = switch (action) {
            case ALLOW -> "Allow. Nothing unusual was found by the configured rules.";
            case REVIEW -> level == Level.HIGH
                    ? "Hold for manual review: several rules agree. In a real system an operator would look before releasing funds."
                    : "Allow but flag for review: unusual activity that is worth a human glance.";
            case BLOCK -> "Block: the score is at or above the blocking threshold. Blocking is applied only when enforcement is enabled.";
        };
        return new Assessment(score, level, action, List.copyOf(hits), List.copyOf(explanation), suggested, score >= p.alertScore());
    }

    private static String window(int seconds) {
        if (seconds % 60 == 0) {
            int m = seconds / 60;
            return m == 1 ? "60 seconds" : m + " minutes";
        }
        return seconds + " seconds";
    }

    static String rupees(long paise) {
        return String.format(Locale.ROOT, "₹%,.2f", paise / 100.0);
    }
}
