package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE INJECTION IS A CONTRACT AND NOT A PRIVATE CONVENIENCE.
 *
 * <p>For two releases {@link Backoff}, {@link Pause} and the predicate were injected only by this
 * package. Everything was replaceable and nothing outside could replace it, which made the design
 * an argument rather than a feature: a consumer whose endpoint is a process on the same host still
 * got eighty-eight seconds of waiting and a minute of jitter chosen for a shared server behind a
 * proxy, and a consumer testing its own pipeline had to live through the waits or not test them.
 *
 * <p>These are the one-liners that fix that, asserted as a consumer would reach them.
 *
 * <p>THE FLAKY ENDPOINT IS NOW ONE LAMBDA'S WORTH OF FAKE, because {@link Chat} is one method. It
 * used to be a five-method streaming client from somebody else's library, handed its failures
 * through a callback; the same eighty-eight-second schedule is asserted below with nothing but a
 * counter and a throw, and none of it is waited through.
 */
class AConsumerCanChooseItsOwnRetryTest {

    @Test
    void noneMeansTheBehaviourFromBeforeAnyOfThisExisted() {
        Flaky endpoint = new Flaky(3);

        assertThrows(RuntimeException.class,
                () -> wrapped(endpoint, new Notes(), Retry.none()).answer(ask()));

        assertEquals(1, endpoint.calls.get(), "asked once, exactly as before Retrying existed");
        // BOTH HALVES, because either alone would hold if the other were broken: none() refuses
        // every failure AND allows one attempt, and a test that checked only the call count would
        // pass with the count raised to five.
        assertEquals(1, Retry.none().attempts(), "one attempt, whatever the predicate says");
        assertFalse(Retry.none().worthRetrying().test(new IllegalStateException("connection reset")),
                "and nothing is worth retrying, whatever the count says");
    }

    @Test
    void localRetriesQuicklyAndWithoutTheJitterMeantForASharedServer() {
        Flaky endpoint = new Flaky(2);
        Waits waits = new Waits();

        wrapped(endpoint, new Notes(), Retry.local().with(waits)).answer(ask());

        assertEquals(3, endpoint.calls.get());
        assertEquals(List.of(1L, 1L), waits.asked,
                "a flat second, and no minute-wide draw in front of a process on this host");
    }

    @Test
    void timesChoosesTheCountAndKeepsTheShippedSchedule() {
        Flaky endpoint = new Flaky(Integer.MAX_VALUE);
        Waits waits = new Waits();

        assertThrows(RuntimeException.class, () -> wrapped(endpoint, new Notes(),
                Retry.times(3).with(waits)).answer(ask()));

        assertEquals(3, endpoint.calls.get(), "three attempts, not the default ten");
        assertEquals(2, waits.asked.size());
        assertTrue(waits.asked.get(0) >= 1 && waits.asked.get(0) <= 60,
                "still the shipped jittered schedule: " + waits.asked);
    }

    @Test
    void aConsumerCanSupplyItsOwnScheduleWhichIsWhereRetryAfterWouldGo() {
        // A rate limit is now a status this library read off the response rather than a type from
        // somebody else's client: Refused(429) IS the "slow down", and transportFailures() judges
        // it by that number alone.
        Flaky endpoint = new Flaky(2, because -> new Refused(429, because), "slow down");
        Waits waits = new Waits();

        // COMPOSES WITH THE SHIPPED SCHEDULE RATHER THAN REPLACING IT, which is the whole shape of
        // this. A rule that answers a flat number throws away the growth AND the draw, and every
        // rate-limited lane then returns at the same second — the herd the jitter exists to break.
        // So: take whichever is longer, and keep a draw on top of the server's number too.
        Backoff shipped = Retry.fromEnv().backoff();
        Backoff honoursTheServer = failed -> {
            Duration ours = shipped.before(failed);
            Duration theirs = retryAfter(failed.get(failed.size() - 1));
            return theirs == null || theirs.compareTo(ours) < 0
                    ? ours
                    : theirs.plusSeconds(DRAW.getAsInt());
        };

        wrapped(endpoint, new Notes(),
                Retry.fromEnv().with(honoursTheServer).with(waits)).answer(ask());

        assertEquals(2, waits.asked.size());
        for (long wait : waits.asked) {
            assertTrue(wait >= 90 && wait < 150,
                    "the server's 90s is honoured and a draw sits on top of it: " + wait);
        }
    }

    @Test
    void composingWithTheShippedScheduleKeepsTheLanesApart() {
        // The failure mode of the obvious version: `? Duration.ofSeconds(90) : ...` answers the
        // same number to every lane, so eight rate-limited lanes return in the same second.
        Backoff shipped = Retry.fromEnv().backoff();
        Backoff composed = failed -> {
            Duration ours = shipped.before(failed);
            Duration theirs = retryAfter(failed.get(failed.size() - 1));
            return theirs == null || theirs.compareTo(ours) < 0
                    ? ours : theirs.plusSeconds(DRAW.getAsInt());
        };

        List<Throwable> oneRateLimit = List.of(new Refused(429, "slow down"));
        Set<Long> drawn = new HashSet<>();
        for (int lane = 0; lane < 200; lane++) {
            drawn.add(composed.before(oneRateLimit).toSeconds());
        }

        assertTrue(drawn.size() > 1,
                "two hundred lanes must not all pick the same second; got " + drawn);
        assertTrue(drawn.stream().allMatch(w -> w >= 90),
                "and none of them may go before the server said: " + drawn);
    }

    /**
     * What a real one would read off a {@code Retry-After} header; null when the server said nothing.
     *
     * <p>The rate limit is recognised by its STATUS here, because that is what the failure now
     * carries: {@link Refused#status()} is the code this library's own client read off the response,
     * rather than a type a third party mapped it onto in an {@code internal} package.
     */
    private static Duration retryAfter(Throwable failure) {
        return failure instanceof Refused refused && refused.status() == 429
                ? Duration.ofSeconds(90) : null;
    }

    /** Stands in for the consumer's own draw. */
    private static final java.util.function.IntSupplier DRAW =
            () -> java.util.concurrent.ThreadLocalRandom.current().nextInt(60);

    @Test
    void pauseNoneLetsAConsumerTestItsOwnPipelineWithoutSleeping() {
        Flaky endpoint = new Flaky(4);
        long before = System.nanoTime();

        wrapped(endpoint, new Notes(), Retry.fromEnv().with(Pause.NONE)).answer(ask());

        long tookMillis = (System.nanoTime() - before) / 1_000_000;
        assertEquals(5, endpoint.calls.get());
        assertTrue(tookMillis < 500,
                "four waits of the real schedule would be minutes; this took " + tookMillis + "ms");
    }

    @Test
    void theShippedShapeCanBeNamedWithoutKnowingWhereItsNumbersComeFrom() {
        Retry named = Retry.fibonacciSeconds();

        assertEquals(10, named.attempts());
        assertEquals(Duration.ofMinutes(30), named.budget());
        assertEquals(Retry.fromEnv().attempts(), named.attempts(),
                "same as fromEnv with nothing set, which is the whole claim");
    }

    @Test
    void theNamedShapeIsNotChangedUnderneathYouByTheEnvironment() {
        // The reason it exists beside fromEnv: a reproducible default. Pinned by construction —
        // fibonacciSeconds(int, int, Duration) reads no setting at all.
        Retry pinned = Retry.fibonacciSeconds(3, 0, Duration.ofMinutes(1));
        Waits waits = new Waits();
        Flaky endpoint = new Flaky(Integer.MAX_VALUE);

        assertThrows(RuntimeException.class,
                () -> wrapped(endpoint, new Notes(), pinned.with(waits)).answer(ask()));

        assertEquals(3, endpoint.calls.get());
        assertEquals(List.of(1L, 1L), waits.asked,
                "a spread of zero is no draw, so the bare Fibonacci shows through");
    }

    @Test
    void aSpreadOfZeroIsTheOnlyWayToGetTheBareScheduleAndItIsWrongForASweep() {
        Retry oneLane = Retry.fibonacciSeconds(6, 0, Duration.ofMinutes(30));
        Waits waits = new Waits();
        Flaky endpoint = new Flaky(Integer.MAX_VALUE);

        assertThrows(RuntimeException.class,
                () -> wrapped(endpoint, new Notes(), oneLane.with(waits)).answer(ask()));

        assertEquals(List.of(1L, 1L, 2L, 3L, 5L), waits.asked,
                "exactly Fibonacci — right for one lane, and the pile-up for eight");
    }

    @Test
    void aConsumerCanTestItsOwnGivingUpWithoutWaitingForTheBudget() {
        // THE CASE THAT WAS UNTESTABLE. With Pause.NONE alone no time passes, so a budget is never
        // reached and the branch that ends a hanging endpoint is never exercised; with a real
        // pause the test takes the budget. The clock is the third seam, and this is what it buys.
        Flaky frozen = new Flaky(Integer.MAX_VALUE);
        Retry fiveMinutes = Retry.fibonacciSeconds()
                .withBudget(Duration.ofMinutes(5))
                .with(Pause.NONE)
                .with(Now.steppingBy(Duration.ofMinutes(2)));

        assertThrows(RuntimeException.class,
                () -> Model.wrap(frozen, new Notes(), fiveMinutes).answer(ask()));

        assertTrue(frozen.calls.get() < 10,
                "the five-minute budget stopped it, not the ten attempts: " + frozen.calls.get());
    }

    @Test
    void aFrozenClockMeansTheBudgetNeverEndsAnything() {
        Flaky flaky = new Flaky(3);
        Retry noTimePasses = Retry.fibonacciSeconds()
                .withBudget(Duration.ofMillis(1))
                .with(Pause.NONE)
                .with(Now.frozenAt(0));

        Model.wrap(flaky, new Notes(), noTimePasses).answer(ask());

        assertEquals(4, flaky.calls.get(),
                "a millisecond budget is never spent when the clock does not move");
    }

    @Test
    void aPolicyThatWouldAskNothingIsRefusedAtTheDoor() {
        assertThrows(IllegalArgumentException.class,
                () -> new Retry(0, Duration.ZERO, Backoff.immediate(), t -> true, Pause.NONE, Now.SYSTEM),
                "zero attempts asks the model nothing at all, which is never what anyone meant");
    }

    @Test
    void theWithMethodsChangeOneFieldAndLeaveTheRest() {
        Retry base = Retry.fromEnv();
        Retry changed = base.withAttempts(2).withBudget(Duration.ofMinutes(5));

        assertEquals(2, changed.attempts());
        assertEquals(Duration.ofMinutes(5), changed.budget());
        assertEquals(base.backoff(), changed.backoff(), "the schedule is untouched");
        assertEquals(base.worthRetrying(), changed.worthRetrying(), "and so is the predicate");
        assertEquals(10, base.attempts(), "and the original is unchanged, being a record");
    }

    // ---------------------------------------------------------------- the fakes

    private static final Now FROZEN = Now.frozenAt(0);

    /**
     * Every wrap in this file freezes the clock; the budget has its own test.
     *
     * <p>{@link Retrying#on(Chat, Retry, Trace)} is the same construction under the name a consumer
     * outside this package reaches for; this stays on {@link Model#wrap} because that is the line
     * the shipped chain actually installs.
     */
    private static Chat wrapped(Chat client, Trace trace, Retry retry) {
        return Model.wrap(client, trace, retry.with(FROZEN));
    }

    private static Ask ask() {
        return Ask.of(List.of(Said.user("what is the ratchet")));
    }

    private static final class Waits implements Pause {
        final List<Long> asked = new ArrayList<>();

        @Override
        public void of(Duration wait) {
            asked.add(wait.toSeconds());
        }
    }

    /**
     * An endpoint that drops the first {@code drops} calls and then answers.
     *
     * <p>It throws where it used to hand a failure to {@code onError}, because a {@link Chat} is a
     * function that either answers or throws — which is also why the whole schedule below runs in
     * milliseconds with no callback, no executor and no socket.
     */
    private static final class Flaky implements Chat {
        final AtomicInteger calls = new AtomicInteger();
        private final int drops;
        private final java.util.function.Function<String, RuntimeException> failure;
        private final String because;

        Flaky(int drops) {
            this(drops, IllegalStateException::new, "connection reset");
        }

        Flaky(int drops, java.util.function.Function<String, RuntimeException> failure, String because) {
            this.drops = drops;
            this.failure = failure;
            this.because = because;
        }

        @Override
        public Reply answer(Ask ask) {
            int call = calls.incrementAndGet();
            if (call <= drops) {
                throw failure.apply(because);
            }
            return new Reply("answer " + call, "", List.of(), Ending.STOPPED, Spend.NONE);
        }
    }

    private static final class Notes implements Trace {
        public void asked(String agent, String prompt, String reply) {
        }

        public void progress(String key, String note) {
        }

        public void applied(String stage, String what) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String agent, String f, String t, String c) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String k, String s, String w, boolean before, boolean after) {
        }

        public void failed(String agent, String k, Throwable c) {
        }

        public void priced(String k, String m, String i) {
        }
    }
}
