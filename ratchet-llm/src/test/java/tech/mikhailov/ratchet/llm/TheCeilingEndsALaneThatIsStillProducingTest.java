package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CEILING IS FOR A LANE THAT IS STILL PRODUCING, AND IT COULD NOT SEE ONE.
 *
 * <p>{@link GaveUp}'s javadoc states the case it exists for: <em>"it fires on a stream that IS
 * producing and has been for hours, and the whole point of it is to give the slot back"</em>. That
 * was the one case the code could not detect.
 *
 * <p>Both guards lived inside the branch taken when {@code lines.poll(tick)} TIMES OUT. For the
 * stall that is right — silence is what a timeout means. For the ceiling it is exactly backwards: a
 * stream delivering a line at least once per tick never times out, so the deadline was never
 * consulted. At the shipped tick of fifteen seconds, a runaway generation emitting a token a second
 * ran past the three-hour ceiling untouched, holding the lane the ceiling exists to reclaim.
 *
 * <p>THE MUTATION REPORT SAID SO WITHOUT BEING ASKED. Every mutant on the ceiling's two lines came
 * back NO_COVERAGE — not survived, not equivalent, but never executed by any test in the module.
 * Code that no test can reach is the shape a dead branch has, and this one was dead for the reason
 * that mattered rather than by accident.
 *
 * <p>The two guards ask opposite questions and now sit in opposite places: the ceiling on every
 * pass, the stall only on silence.
 */
class TheCeilingEndsALaneThatIsStillProducingTest {

    @Test
    @Timeout(20)
    void aStreamThatNeverStopsProducingIsStillEndedByTheCeiling() {
        // A frame every 40ms against a 200ms stall and a 700ms ceiling. Watch refuses a ceiling
        // shorter than its stall — "or the ceiling is the only guard that ever fires" — so the
        // stall has to stay the shorter of the two, and the stream has to out-talk it. At 40ms it
        // does: never silent for 200ms, so the stall cannot fire and only the ceiling can end this.
        // Under the old code nothing could, which is the point.
        Wire wire = new Wire(Endpoint.of("http://test/v1", "m"), Sampling.deterministic(),
                new Watch(Duration.ofMillis(200), Duration.ofMillis(700)), true, null);

        GaveUp gave = assertThrows(GaveUp.class, () -> wire.read(chatty()));

        assertTrue(gave.getMessage().contains("still streaming"),
                "the lane was producing throughout and the ceiling is what ends it: "
                        + gave.getMessage());
    }

    @Test
    @Timeout(20)
    void theBoundIsReportedInAUnitThatSurvivesBeingSmall() {
        // Since Watch became a per-call value (ratchet#7), a consumer may set a patience of
        // minutes. The message rendered `ceiling.toHours()`, so anything under an hour announced
        // itself as "still streaming after 0h" — which reads as a broken guard rather than a fact.
        Wire wire = new Wire(Endpoint.of("http://test/v1", "m"), Sampling.deterministic(),
                new Watch(Duration.ofMillis(200), Duration.ofMillis(700)), true, null);

        GaveUp gave = assertThrows(GaveUp.class, () -> wire.read(chatty()));

        assertTrue(!gave.getMessage().contains("0h") && !gave.getMessage().contains("0 minutes"),
                "a bound that reports itself as zero is not a report: " + gave.getMessage());
    }

    @Test
    @Timeout(20)
    void aSilentStreamIsStillTheStallAndNotTheCeiling() {
        // THE OTHER HALF, so a fix cannot swap them. Silence must be reported as silence: the
        // reader's next move differs completely between a dead socket and a lane that has run long.
        Wire wire = new Wire(Endpoint.of("http://test/v1", "m"), Sampling.deterministic(),
                new Watch(Duration.ofMillis(200), Duration.ofSeconds(30)), true, null);

        IllegalStateException stalled = assertThrows(IllegalStateException.class,
                () -> wire.read(silent()));

        assertTrue(stalled.getMessage().contains("not producing"),
                "nothing arrived, so this is a stall: " + stalled.getMessage());
        assertTrue(!(stalled instanceof GaveUp), "and not the ceiling: " + stalled);
    }

    /** A stream that keeps talking for ever: never silent, so only a ceiling can end it. */
    private static Stream<String> chatty() {
        return Stream.generate(() -> {
            park(40);
            return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},"
                    + "\"finish_reason\":null}]}";
        });
    }

    /** A stream that never yields anything and never ends. */
    private static Stream<String> silent() {
        return Stream.generate(() -> {
            park(50);
            return "";
        });
    }

    private static void park(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(stopping);
        }
    }
}
