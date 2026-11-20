package com.paysure.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.paysure.risk.RiskEngine.Action;
import com.paysure.risk.RiskEngine.Assessment;
import com.paysure.risk.RiskEngine.Facts;
import com.paysure.risk.RiskEngine.Level;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the rule engine: no database, no Spring. */
class RiskEngineTest {

    private static final RiskProperties P = new RiskProperties(true, RiskProperties.Mode.ALERT, 40, 70, 90,
            8, 60, 35, 3, 60, 20, 3, 600, 20, 5_000_000L, 25, 15, 3, 300, 20);

    private static Facts calm() {
        return new Facts(10_000, 1, 1, 0, false, 1);
    }

    private static List<String> codes(Assessment a) {
        return a.rules().stream().map(RiskEngine.RuleHit::code).toList();
    }

    @Test
    void anOrdinaryPaymentScoresZeroAndIsAllowed() {
        Assessment a = RiskEngine.evaluate(calm(), P);
        assertThat(a.score()).isZero();
        assertThat(a.level()).isEqualTo(Level.LOW);
        assertThat(a.action()).isEqualTo(Action.ALLOW);
        assertThat(a.rules()).isEmpty();
        assertThat(a.alertWorthy()).isFalse();
        assertThat(a.explanation()).containsExactly("Risk score: 0/100 (low).", "No risk rule was triggered.");
    }

    @Test
    void theWorkedExampleFromTheSpecificationIsExplainedLineByLine() {
        // 9 transfers in 60 s, a new recipient, an amount above the demo threshold.
        Assessment a = RiskEngine.evaluate(new Facts(6_000_000, 9, 1, 0, true, 2), P);
        assertThat(a.score()).isEqualTo(35 + 25 + 15);
        assertThat(codes(a)).containsExactly("HIGH_VELOCITY", "LARGE_AMOUNT", "NEW_RECIPIENT");
        assertThat(a.level()).isEqualTo(Level.HIGH);
        assertThat(a.action()).isEqualTo(Action.REVIEW);
        assertThat(a.alertWorthy()).isTrue();
        assertThat(a.explanation()).containsExactly(
                "Risk score: 75/100 (high).",
                "9 transfers initiated in 60 seconds (threshold 8). (+35)",
                "Amount ₹60,000.00 is at or above the demo threshold of ₹50,000.00. (+25)",
                "You have not completed a payment to this recipient before. (+15)");
        assertThat(a.suggestedAction()).startsWith("Hold for manual review");
    }

    @Test
    void eachRuleFiresExactlyAtItsThresholdAndNotBelow() {
        assertThat(codes(RiskEngine.evaluate(new Facts(1, 7, 1, 0, false, 1), P))).isEmpty();
        assertThat(codes(RiskEngine.evaluate(new Facts(1, 8, 1, 0, false, 1), P))).containsExactly("HIGH_VELOCITY");
        assertThat(codes(RiskEngine.evaluate(new Facts(1, 1, 2, 0, false, 1), P))).isEmpty();
        assertThat(codes(RiskEngine.evaluate(new Facts(1, 1, 3, 0, false, 1), P))).containsExactly("RAPID_REPEAT");
        assertThat(codes(RiskEngine.evaluate(new Facts(1, 1, 1, 2, false, 1), P))).isEmpty();
        assertThat(codes(RiskEngine.evaluate(new Facts(1, 1, 1, 3, false, 1), P))).containsExactly("MULTIPLE_FAILURES");
        assertThat(codes(RiskEngine.evaluate(new Facts(4_999_999, 1, 1, 0, false, 1), P))).isEmpty();
        assertThat(codes(RiskEngine.evaluate(new Facts(5_000_000, 1, 1, 0, false, 1), P))).containsExactly("LARGE_AMOUNT");
    }

    @Test
    void aNewRecipientBurstNeedsBothANewRecipientAndRecentActivity() {
        assertThat(codes(RiskEngine.evaluate(new Facts(1, 1, 1, 0, true, 2), P))).containsExactly("NEW_RECIPIENT");
        assertThat(codes(RiskEngine.evaluate(new Facts(1, 1, 1, 0, true, 3), P))).containsExactly("NEW_RECIPIENT", "NEW_RECIPIENT_BURST");
        // Lots of activity towards a KNOWN recipient is not a new-recipient burst.
        assertThat(codes(RiskEngine.evaluate(new Facts(1, 1, 1, 0, false, 50), P))).isEmpty();
    }

    @Test
    void levelsAndActionsFollowTheConfiguredScoreBands() {
        // 15 (new) + 20 (burst) = 35 -> below the alert threshold
        Assessment low = RiskEngine.evaluate(new Facts(1, 1, 1, 0, true, 3), P);
        assertThat(low.score()).isEqualTo(35);
        assertThat(low.level()).isEqualTo(Level.LOW);
        assertThat(low.alertWorthy()).isFalse();
        // 35 + 20 = 55 -> MEDIUM / REVIEW
        Assessment medium = RiskEngine.evaluate(new Facts(1, 8, 3, 0, false, 1), P);
        assertThat(medium.score()).isEqualTo(55);
        assertThat(medium.level()).isEqualTo(Level.MEDIUM);
        assertThat(medium.action()).isEqualTo(Action.REVIEW);
        assertThat(medium.suggestedAction()).startsWith("Allow but flag");
        // everything at once, capped at 100 and suggesting BLOCK
        Assessment all = RiskEngine.evaluate(new Facts(9_000_000, 12, 9, 5, true, 12), P);
        assertThat(all.score()).isEqualTo(100);
        assertThat(all.level()).isEqualTo(Level.HIGH);
        assertThat(all.action()).isEqualTo(Action.BLOCK);
        assertThat(all.explanation()).anyMatch(l -> l.contains("capped at 100"));
    }

    @Test
    void thresholdsAndWeightsAreConfigurable() {
        RiskProperties strict = new RiskProperties(true, RiskProperties.Mode.ENFORCE, 10, 20, 30,
                2, 60, 50, 2, 60, 5, 1, 600, 5, 100, 5, 5, 2, 300, 5);
        Assessment a = RiskEngine.evaluate(new Facts(100, 2, 1, 0, false, 1), strict);
        assertThat(codes(a)).containsExactly("HIGH_VELOCITY", "LARGE_AMOUNT");
        assertThat(a.score()).isEqualTo(55);
        assertThat(a.action()).isEqualTo(Action.BLOCK);
    }

    @Test
    void windowsAreDescribedInPlainLanguage() {
        Assessment a = RiskEngine.evaluate(new Facts(1, 1, 1, 3, true, 3), P);
        assertThat(a.explanation()).anyMatch(l -> l.contains("3 payments failed in the last 10 minutes"))
                .anyMatch(l -> l.contains("in 5 minutes while paying a new recipient"));
    }
}
