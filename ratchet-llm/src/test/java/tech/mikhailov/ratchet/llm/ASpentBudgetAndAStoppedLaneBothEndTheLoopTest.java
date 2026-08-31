package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SPENT BUDGET AND A STOPPED LANE BOTH END THE LOOP, AND EACH WAS ONE ATTEMPT FROM NOT ENDING IT.
 *
 * <p>THE BUDGET IS THE ONLY BOUND ON WALL-CLOCK TIME THIS LIBRARY HAS. {@link Watch#ceiling()} is
 * per attempt and this loop starts a new one, so the arithmetic {@link Retrying}'s own javadoc sets
 * out — a frozen endpoint costs a twenty-minute stall per attempt, ten attempts is three and a half
 * hours — is bounded here or nowhere. One attempt past the bound is therefore not a rounding error;
 * it is up to another stall of a slot nobody can reclaim.
 *
 * <p>THE BOUND HAD BEEN ASSERTED AS AN INEQUALITY, WHICH IS NOT A BOUND. The one test that reached
 * this branch stopped a ten-attempt sequence with a five-minute budget and a two-minute step, then
 * asserted the endpoint was called <em>fewer than ten</em> times. That sentence is true when the
 * loop stops on the attempt that spends the budget and equally true when it stops on the one after,
 * so {@code now.millis() >= deadline} and {@code now.millis() > deadline} both passed it. The tests
 * below land the clock exactly ON the deadline, where those two differ and nowhere else does.
 *
 * <p>A STOPPED LANE IS THE SAME SHAPE OF MISS AND A LOUDER ONE. {@code attempt} catches
 * {@link RuntimeException}, so a checked {@link InterruptedException} can never BE the failure the
 * judgement classifies — it can only be a CAUSE under whatever the client threw. Nothing in the
 * suite had ever handed {@link Retrying#transportFailures()} an interruption in either shape, bare
 * or buried, so deleting {@code at instanceof InterruptedException} from the chain changed no test.
 * With it gone the interrupt is merely unrecognised, and this class retries what it does not
 * recognise on purpose: ten attempts and the shipped eighty-eight seconds of Fibonacci waiting,
 * begun after the lane was told to stop.
 *
 * <p>THAT IS WHAT THE WALK DOWN THE CAUSE CHAIN IS FOR, and the walk has two ends of its own. It
 * must not stop at the top, or a classification one link down is never read; it must not fail to
 * stop, or the judgement never returns at all — and a predicate that never returns is a lane held
 * for ever, past every bound above, because the budget is checked after it.
 *
 * <p>EVERYTHING HERE GOES THROUGH {@link Retrying#around}. No {@link Ask}, no {@link Reply}: all of
 * it lives in the one static loop both doors share, and this is the door that reaches it without a
 * message model at all.
 */
class ASpentBudgetAndAStoppedLaneBothEndTheLoopTest {

    /**
     * A clock a minute a look, against a two-minute budget.
     *
     * <p>THE ARITHMETIC IS THE POINT OF THE FIXTURE, so it is written down. {@code attempt} reads
     * the clock once to set the deadline and once per failed attempt that is going to be retried:
     * read one is 0 and fixes the deadline at two minutes, read two is one minute, read three is
     * two minutes — landing the third look exactly on the deadline, which is the only place the
     * shipped {@code >=} and a {@code >} disagree.
     */
    private static final Duration A_MINUTE_A_LOOK = Duration.ofMinutes(1);

    private static final Duration TWO_MINUTES = Duration.ofMinutes(2);

    @Test
    void theSequenceEndsOnTheAttemptThatSpendsTheBudgetAndNotOnTheOneAfterIt() {
        AtomicInteger calls = new AtomicInteger();
        Waits waits = new Waits();

        assertThrows(IllegalStateException.class,
                () -> Retrying.around(dropping(calls), spending(TWO_MINUTES, waits), Trace.quiet())
                        .get());

        assertEquals(2, calls.get(),
                "the second attempt is the one that spends the two minutes, so it is the last one; "
                        + "a third would start a fresh twenty-minute stall on a budget already gone");
        assertEquals(List.of(1L), waits.seconds,
                "one wait, before the attempt that spent it — nothing sleeps after the budget is up");
    }

    @Test
    void theRecordSaysTheBudgetIsWhyItStoppedAndWhichAttemptSpentIt() {
        // A SEQUENCE THAT STOPPED AT TWO OF TEN LOOKS EXACTLY LIKE A BROKEN COUNT unless the record
        // says which bound ended it. This note is the only place that distinction is written down,
        // and Retrying's own javadoc gives the reason it is written at all: "a retry nobody can see
        // is an endpoint whose flakiness never shows up anywhere, and the first anyone hears of it
        // is a bill or a lane that takes an hour."
        Notes notes = new Notes();

        assertThrows(IllegalStateException.class,
                () -> Retrying.around(dropping(new AtomicInteger()),
                        spending(TWO_MINUTES, new Waits()), notes).get());

        assertEquals(2, notes.progress.size(),
                "one note for the retry and one for the giving up: " + notes.progress);
        assertTrue(notes.progress.get(1).contains("spent 2m of retries"),
                "the budget, and that it is spent, in the note that ends it: " + notes.progress);
        assertTrue(notes.progress.get(1).contains("attempt 2 of 10"),
                "and eight attempts left unused, which is the fact that says the budget stopped it "
                        + "rather than the count: " + notes.progress);

        // ASSERTED IN MINUTES BECAUSE THAT IS WHAT THE NOTE CAN SAY, AND ONLY WHOLE ONES.
        // budget.toMinutes() truncates, so a consumer that sets 45 seconds — Retry.withBudget takes
        // any Duration — is told "spent 0m of retries", which reads as a broken guard rather than a
        // spent bound. It is the same defect GaveUp had when a per-call ceiling under an hour
        // announced itself as "after 0h", and it is reported rather than asserted here: a test that
        // pinned "0m" would be green on the output it exists to prevent.
    }

    @Test
    void aBudgetThatIsNotSpentTakesNothingAwayFromTheCount() {
        // THE OTHER SIDE OF THE BOUND. A budget that fires early is worse than one that fires late:
        // it throws away the attempts that were going to succeed, and it does it on a clock the
        // caller cannot see. Same clock, same step, a budget nothing reaches.
        AtomicInteger calls = new AtomicInteger();

        IllegalStateException surfaced = assertThrows(IllegalStateException.class,
                () -> Retrying.around(numbered(calls),
                        spending(Duration.ofMinutes(30), new Waits()).withAttempts(4),
                        Trace.quiet()).get());

        assertEquals(4, calls.get(), "four attempts asked for and four made");
        assertEquals("dropped on attempt 4", surfaced.getMessage(),
                "and what escapes is the failure that ended it: " + surfaced.getMessage());
    }

    @Test
    void anInterruptionBuriedInACauseStopsTheLaneRatherThanServingOutTheSchedule() {
        // THE SHAPE AN INTERRUPTION REALLY ARRIVES IN. attempt catches RuntimeException, so a
        // checked InterruptedException is never the thing being judged — a client that was reading
        // a response when the lane was stopped reports it wrapped, and the wrapper is a transport
        // failure like any other to look at. The judgement has to go and find it.
        AtomicInteger calls = new AtomicInteger();
        RuntimeException stopped = new IllegalStateException("interrupted while reading the response",
                new InterruptedException("--stop"));
        Waits waits = new Waits();

        RuntimeException surfaced = assertThrows(RuntimeException.class,
                () -> Retrying.around(throwing(calls, stopped),
                        spending(Duration.ofMinutes(30), waits), Trace.quiet()).get());

        assertEquals(1, calls.get(),
                "a lane being stopped stops; the alternative is nine more attempts and 88 seconds "
                        + "of waiting bought after the stop was asked for");
        assertEquals(List.of(), waits.seconds, "and nothing sleeps");
        assertSame(stopped, surfaced, "with the client's own failure handed back, cause and all");
    }

    @Test
    void anInterruptionIsRefusedByTheJudgementInEitherShapeItCanHave() {
        // transportFailures() is a public Predicate<Throwable> — ratchet#8 exists so a consumer can
        // compose it around failures this library never throws — so it is asked about both shapes
        // directly, not only about the one the loop can reach.
        assertFalse(Retrying.transportFailures().test(new InterruptedException("--stop")),
                "an interruption is a stop, whoever hands it over");
        assertFalse(Retrying.transportFailures()
                        .test(new IllegalStateException("read failed", new InterruptedException("--stop"))),
                "and it is still a stop one link down, which is the only depth the loop can see it at");
    }

    @Test
    void theJudgementReadsTheWholeChainAndNotJustTheTopOfIt() {
        // The early return answers on the FIRST classified failure, which is what makes the outermost
        // status win. The walk underneath it is what makes there be a first one at all: a client that
        // wraps twice — a transport exception around an I/O exception around the refusal it read —
        // presents nothing classified at the top, and stopping there means a 401 retried ten times.
        RuntimeException twoDeep = new IllegalStateException("the request failed",
                new IllegalStateException("reading the response", new Refused(401, "bad key")));

        assertFalse(Retrying.transportFailures().test(twoDeep),
                "the refusal is two links down and it still decides");

        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class,
                () -> Retrying.around(throwing(calls, twoDeep),
                        spending(Duration.ofMinutes(30), new Waits()), Trace.quiet()).get());
        assertEquals(1, calls.get(), "nine more attempts cannot fix a credential at any depth");

        RuntimeException retriableTwoDeep = new IllegalStateException("the request failed",
                new IllegalStateException("reading the response", new Refused(503, "unavailable")));
        assertTrue(Retrying.transportFailures().test(retriableTwoDeep),
                "and the walk is not a way of refusing everything deep: a 503 down there is still "
                        + "the server failing, and still worth another request");
    }

    @Test
    void theWalkEndsOnAChainThatPointsBackAtItself() {
        // A JUDGEMENT THAT NEVER RETURNS IS WORSE THAN EITHER ANSWER IT COULD HAVE GIVEN. The budget
        // is checked after this predicate, the ceiling is per attempt and no attempt is running, so
        // a lane whose retry is deciding for ever is held by nothing and released by nothing.
        //
        // Throwable.getCause() returning the throwable itself is why the guard is there. The fixture
        // counts the looks instead of relying on a timeout, so the failure is an assertion rather
        // than a suite that hangs: the walk reads a link's cause at most twice, once to compare and
        // once to advance, and this chain has one link.
        ItsOwnCause circular = new ItsOwnCause();

        assertTrue(Retrying.transportFailures().test(circular),
                "unrecognised, so retried — and it has to reach an answer to say so");

        // THE GUARD SEES ONE LINK AND CYCLES CAN BE LONGER. A chain of two — first.initCause(second)
        // and second.initCause(first), which the JDK allows because initCause refuses only
        // self-causation — walks for ever here, and it is reported rather than asserted because the
        // assertion that states it does not terminate against the code as it stands.
    }

    @Test
    void fiveHundredIsTheServerFailingAndIsAskedAgain() {
        // The line is at 500 and the class it opens is unbounded above. 500 itself is the case the
        // classification was rebuilt for: reading the message rather than the status meant a 500
        // whose body quoted a 401 was filed as permanent and never retried at all.
        assertTrue(Retrying.transportFailures().test(new Refused(500, "internal server error")),
                "a plain 500 is the server failing, and the server may not fail the second time");
        assertTrue(Retrying.transportFailures().test(new Refused(599, "gateway gave up")),
                "and nothing above it is the request's fault either");
        assertFalse(Retrying.transportFailures().test(new Refused(499, "client closed request")),
                "the last of the 4xx is still the client's side of the line: same request, same "
                        + "answer, nine more times");
    }

    @Test
    void aSpentRoundBudgetIsRefusedThroughTheDoorThatInheritedTheRetry() {
        // {@link Exhausted} is refused by the judgement, and that has an assertion of its own. What
        // it does not have is the wiring: Asking sits ABOVE Retrying in the shipped chain, so this
        // failure only ever meets the loop through around(), which is the door Exhausted's javadoc
        // names as the reason it had to become a type. Ten attempts here is two hundred and fifty
        // rounds of model calls to reach the same wall.
        AtomicInteger calls = new AtomicInteger();

        assertThrows(Exhausted.class,
                () -> Retrying.around(throwing(calls, new Exhausted("exceeded 25 rounds")),
                        spending(Duration.ofMinutes(30), new Waits()), Trace.quiet()).get());

        assertEquals(1, calls.get(),
                "one conversation was enough to learn this; a second re-runs all twenty-five rounds");
    }

    // ---------------------------------------------------------------- the fixtures

    /** Ten attempts on the shipped judgement and schedule, with the clock and the waits handed in. */
    private static Retry spending(Duration budget, Waits waits) {
        return new Retry(10, budget, Backoff.fibonacciSeconds(), Retrying.transportFailures(),
                waits, Now.steppingBy(A_MINUTE_A_LOOK));
    }

    /** An endpoint that is always down, in the way a retry is for. */
    private static Supplier<String> dropping(AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("connection reset");
        };
    }

    /** The same, saying which attempt it was, so the failure that escapes can be identified. */
    private static Supplier<String> numbered(AtomicInteger calls) {
        return () -> {
            throw new IllegalStateException("dropped on attempt " + calls.incrementAndGet());
        };
    }

    /** Always the same object, so what the caller is handed can be compared to it. */
    private static Supplier<String> throwing(AtomicInteger calls, RuntimeException failure) {
        return () -> {
            calls.incrementAndGet();
            throw failure;
        };
    }

    /**
     * A failure whose cause is itself.
     *
     * <p>{@link Throwable} will not build one — {@code initCause} refuses self-causation and the
     * no-cause constructors report null — so it takes an override, which is also how it happens in
     * the wild: a wrapper that answers {@code getCause()} with itself rather than with what it
     * wrapped. It counts what asks, and fails the test rather than the suite's patience.
     */
    private static final class ItsOwnCause extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Two looks per link, one link. */
        private static final int LOOKS_A_TERMINATING_WALK_NEEDS = 2;

        private int looks;

        ItsOwnCause() {
            super("a failure whose chain points back at itself");
        }

        @Override
        public synchronized Throwable getCause() {
            if (++looks > LOOKS_A_TERMINATING_WALK_NEEDS) {
                looks = 0;
                throw new AssertionError("the walk did not end on a chain that points at itself: "
                        + "the cause was read more than " + LOOKS_A_TERMINATING_WALK_NEEDS
                        + " times, which on one link means it is going round");
            }
            return this;
        }
    }

    /** The waits, taken rather than lived through. */
    private static final class Waits implements Pause {
        private final List<Long> seconds = new ArrayList<>();

        @Override
        public void of(Duration wait) {
            seconds.add(wait.toSeconds());
        }
    }

    /**
     * A trace that keeps the notes. {@link Trace#quiet()} is the one for the tests above, which are
     * asserting something else; this one exists because two of them assert the record itself.
     */
    private static final class Notes implements Trace {
        private final List<String> progress = new ArrayList<>();

        @Override
        public void progress(String key, String note) {
            progress.add(note);
        }

        @Override
        public void asked(String agent, String prompt, String reply) {
        }

        @Override
        public void applied(String stage, String what) {
        }

        @Override
        public void tool(String agent, String tool, String arguments, String result) {
        }

        @Override
        public void thought(String agent, String finishReason, String thinking, String content) {
        }

        @Override
        public void built(String phase, Outcome result) {
        }

        @Override
        public void settled(String key, String state, String because, boolean beforeOk,
                            boolean afterOk) {
        }

        @Override
        public void failed(String key, Throwable cause) {
        }

        @Override
        public void priced(String key, String minutes, String itemisation) {
        }
    }
}
