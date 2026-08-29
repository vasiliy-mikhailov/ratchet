package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CEILING IS FOR A LANE THAT IS STILL PRODUCING, AND IT COULD NOT SEE ONE.
 *
 * <p>{@link GaveUp}'s javadoc states the case it exists for: <em>"it fires on a stream that IS
 * producing and has been for hours, and the whole point of it is to give the slot back"</em>. That
 * was the one case the code could not detect. Both guards lived inside the branch taken when
 * {@code lines.poll(tick)} TIMES OUT — right for the stall, since silence is what a timeout means,
 * and exactly backwards for the ceiling: a stream delivering a line every tick never times out, so
 * the deadline was never consulted. At the shipped fifteen-second tick, a runaway emitting a token
 * a second ran past three hours untouched, holding the lane the ceiling exists to reclaim.
 *
 * <p>THE MUTATION REPORT SAID SO WITHOUT BEING ASKED: every mutant on those two lines came back
 * NO_COVERAGE — not survived, not equivalent, never executed by any test in the module.
 *
 * <p>THIS FILE ASSERTS THE SHIPPED THREE HOURS, WHICH IT COULD NOT DO BEFORE. Its first version ran
 * against a 700-millisecond ceiling and a stream sleeping 40ms a frame, with a {@code @Timeout} on
 * every method because the failure mode was a hang. That bound was chosen for the patience of
 * whoever runs the suite and described nothing. {@link Wire} takes a {@link Now} now, so the
 * requirement can be stated as the requirement: three hours, no sleeps, no latch, no timeout.
 *
 * <p>THE CLOCK STEPS PER READ AND THE LOOP READS IT TWICE A PASS — three times when a data frame
 * updates the last-token mark. That is the unit these fixtures are sized against, and getting it
 * wrong cost two attempts: {@code steppingBy(1 hour)} made a stream sending a frame an hour look
 * like one sending a frame every three, so the stall fired where the ceiling was being tested. The
 * alternative — advancing the clock as the fixture yields — is worse, because the body is drained
 * by its own thread as fast as it will go, so an unbounded stream races the clock past every bound
 * before the loop has looked once.
 *
 * <p>Five minutes a read keeps a producing lane's silence near ten minutes, comfortably inside the
 * shipped twenty, while three hours still arrives in a dozen passes.
 */
class TheCeilingEndsALaneThatIsStillProducingTest {

    /**
     * BOUNDED, AND THE BOUND IS ABOUT THE READER RATHER THAN THE TEST.
     *
     * <p>{@link Wire} drains the body on its own thread with {@code forEach}, and closes the body on
     * the way out. A real {@code BodyHandlers.ofLines()} stream throws into that thread when it is
     * closed, so production stops. {@code Stream.generate} has no close handler and does not: the
     * reader kept producing into an unbounded queue for the life of the JVM.
     *
     * <p>Measured on the shipped Watch: the ceiling fired after 223,899 frames, and in the 300ms
     * AFTER read() returned the reader produced 5,346,755 more and took the heap to 140 MB — 252 MB
     * at 1.3s and still climbing. Every test that ran afterwards competed with it, and it is the
     * likeliest reason sixteen of Wire's mutants came back MEMORY_ERROR: unscoreable rather than
     * equivalent, including the one that removes the ceiling outright.
     *
     * <p>The limit is far beyond any guard under test, so the guard still fires first and the test
     * still means what it says — it just cannot run away.
     */
    private static final long FRAMES = 1_000_000;

    /** What ships: twenty minutes of silence allowed, three hours of anything at all. */
    private static final Watch SHIPPED = Watch.shipped();

    @Test
    void aStreamThatNeverStopsProducingIsStillEndedAtTheCeiling() {
        // Tokens arriving throughout, silence never near twenty minutes, and the lane still ends —
        // which under the old code it could not, because the ceiling was only consulted when the
        // poll timed out and this stream never lets it.
        Wire wire = wire(SHIPPED, Now.steppingBy(Duration.ofMinutes(5)));

        GaveUp gave = assertThrows(GaveUp.class, () -> wire.read(tokensForever()));

        assertTrue(gave.getMessage().contains("3h"),
                "the shipped three-hour ceiling, stated as three hours: " + gave.getMessage());
    }

    @Test
    void aLaneUnderTheCeilingIsLeftAlone() {
        // THE OTHER SIDE OF THE BOUND, which the old test could not express. A guard that fires
        // early is worse than one that fires late: it kills work that was going to succeed.
        Wire wire = wire(SHIPPED, Now.steppingBy(Duration.ofMinutes(5)));

        Reply reply = wire.read(saysThenEnds());

        assertEquals("an answer", reply.said(), "well inside both bounds, this call was fine");
    }

    @Test
    void aConnectionSendingKeepAlivesButNoTokensStillStalls() {
        // THE STALL MEASURES TOKENS AND USED TO MEASURE LINES. It lived in the poll-timeout branch,
        // so a connection that kept ARRIVING never stalled however long it went without producing.
        // That is what an SSE keep-alive is: blank separators and comment frames exist so an idle
        // connection stays open, and any proxy in the path may add them. Such a stream ran to the
        // three-hour ceiling instead of stopping at twenty minutes — the ceiling doing the stall's
        // job nine times slower.
        Wire wire = wire(SHIPPED, Now.steppingBy(Duration.ofMinutes(5)));

        IllegalStateException stalled = assertThrows(IllegalStateException.class,
                () -> wire.read(keepAlivesForever()));

        assertTrue(stalled.getMessage().contains("not producing"),
                "lines were arriving and tokens were not, which is a stall: "
                        + stalled.getMessage());
        assertTrue(!(stalled instanceof GaveUp),
                "and not the ceiling, three hours later: " + stalled);
    }

    @Test
    void aCeilingUnderAnHourSaysMinutesRatherThanZeroHours() {
        // Since Watch became a per-call value a consumer may set minutes, and the message rendered
        // ceiling.toHours() — so anything under an hour announced itself as "after 0h", which reads
        // as a broken guard rather than a fact about the connection.
        Wire wire = wire(new Watch(Duration.ofMinutes(4), Duration.ofMinutes(5)),
                Now.steppingBy(Duration.ofMinutes(1)));

        GaveUp gave = assertThrows(GaveUp.class, () -> wire.read(tokensForever()));

        assertTrue(gave.getMessage().contains("5m"),
                "a five-minute ceiling says five minutes: " + gave.getMessage());
    }

    private static Wire wire(Watch watch, Now now) {
        return new Wire(Endpoint.of("http://test/v1", "a-model"), Sampling.deterministic(),
                watch, true, null, now);
    }

    /** Tokens for ever: never silent, so only a ceiling can end it. */
    private static Stream<String> tokensForever() {
        return Stream.generate(() -> "data: {\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}").limit(FRAMES);
    }

    /**
     * Lines that are not tokens, for ever — an SSE keep-alive.
     *
     * <p>A blank line is the frame separator. The loop skips it without touching the last-token
     * mark, which is what makes it silence to the guard and traffic to the socket.
     */
    private static Stream<String> keepAlivesForever() {
        return Stream.generate(() -> "").limit(FRAMES);
    }

    /** One short, ordinary, successful response. */
    private static Stream<String> saysThenEnds() {
        return Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"an answer\"},"
                        + "\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "data: [DONE]");
    }
}
