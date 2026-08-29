package tech.mikhailov.ratchet.llm;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * THE DECORATORS ARE PROVED TO BE INSTALLED, AND NOT ONLY TO WORK.
 *
 * <p>{@link Retrying} had seventeen tests and none of them touched {@link Model}. The whole
 * decorator could be deleted from the chain and every one of those tests still passed, because
 * {@code build} cannot be called without a live base URL and so nothing ever asserted what it
 * assembles. A guard that is correct and not connected is not a guard.
 *
 * <p>This asks the question the other file cannot: given the chain {@code Model} actually assembles,
 * does a dropped connection get asked again? It runs through the real predicate and the real
 * schedule, and it feeds them the exact failures {@link Wire} throws: an
 * {@link IllegalStateException} wrapping the {@link IOException} for a socket that died, and a
 * {@link Refused} carrying the status for a request the endpoint turned away. The layer that used
 * to sit between the two — a wrapper around a third party's streaming client, which turned a
 * handler callback into a throw — is gone, because {@link Chat} is one blocking method and the
 * streaming lives inside {@link Wire} now. What that buys this test is that the thing handed to
 * {@code Model.wrap} here has the same shape as the thing production hands it.
 *
 * <p>Only the three things that take wall-clock time — the jitter draw, the wait and the clock —
 * are handed in, because the production draw is up to a minute wide and a test that lived through
 * one would be a test somebody eventually deletes.
 */
class TheChainModelBuildsReallyRetriesTest {

    @Test
    void aDroppedConnectionIsAskedAgainByTheChainModelBuilds() {
        Flaky endpoint = new Flaky(1);
        Notes notes = new Notes();

        Waits waits = new Waits();
        Reply answer = wrapped(endpoint, notes, plain(waits)).answer(ask());

        assertEquals("answer 2", answer.said(),
                "the retry's answer, through the chain build() assembles");
        assertEquals(2, endpoint.calls.get(), "the endpoint really was asked twice");
        assertEquals(List.of(1L), waits.asked,
                "and the wait is the first Fibonacci second, through the production schedule");
    }

    @Test
    void aRefusedRequestIsNotRetriedByTheChainEither() {
        // A 401 IN THE SHAPE THE CLIENT NOW REPORTS IT. This used to be a third party's typed
        // exception, and the predicate had to test that type AND a raw status code because the
        // mapping between them ran in an internal package with no promise it had run at all.
        // Wire reads the status off the response, so what the chain is fed here is what production
        // feeds it, and the judgement it is being proved to have installed is one comparison.
        Flaky endpoint = new Flaky(Integer.MAX_VALUE, body -> new Refused(401, body), "bad key");
        Notes notes = new Notes();

        Refused refused = assertThrows(Refused.class,
                () -> wrapped(endpoint, notes, plain(new Waits())).answer(ask()));

        assertEquals(401, refused.status(),
                "the status reaches the caller intact, which is the field the comparison is made on");
        assertEquals(1, endpoint.calls.get(),
                "the production predicate is wired in, not just the production count");
    }

    @Test
    void theDefaultIsTenAttempts() {
        assertEquals(10, Model.numberFrom("10", 10));
        assertEquals(10, Model.attempts(), "unset in this JVM, so the documented default is in force");
    }

    @Test
    void aValueNobodyCanReadFallsBackInsteadOfKillingTheProcess() {
        assertEquals(10, Model.numberFrom("ten", 10), "a typo must not take the sweep down");

        // AND IT FALLS BACK TO THE CALLER'S NUMBER, NOT TO TEN. One parser serves six settings and
        // ten is the default of exactly one of them, so an unreadable value used to do ATTEMPTS's
        // thing whatever it was parsing: CEILING_HOURS=3h became a TEN hour ceiling, and
        // THINKING_TOKENS=4k became ten thinking tokens — which is not a smaller budget but no
        // budget, so every generation is cut off mid-thought and comes back blank. That is the
        // Truncated failure this library documents, arrived at from a typo, with the trace
        // reporting a model that would not answer.
        assertEquals(3, Model.numberFrom("3h", 3), "a ceiling named in hours falls back to ITS hours");
        assertEquals(4000, Model.numberFrom("4k", 4000), "and a thinking budget to ITS tokens");
        assertEquals(30, Model.numberFrom("half an hour", 30));
        assertEquals(10, Model.numberFrom("", 10), "nor an empty one");
        assertEquals(3, Model.numberFrom("  3  ", 10), "and whitespace is not a typo");
    }

    @Test
    void oneIsAValidAnswerAndMeansTheBehaviourFromBefore() {
        assertEquals(1, Model.numberFrom("1", 10));
        assertEquals(1, Model.numberFrom("0", 10), "and nothing below one, which would ask zero times");
        assertEquals(1, Model.numberFrom("-4", 10));
    }

    @Test
    void theProductionScheduleCarriesAJitterDrawOnTopOfEachFibonacciSecond() {
        Flaky endpoint = new Flaky(3);
        Waits waits = new Waits();

        wrapped(endpoint, new Notes(), Retry.fromEnv().with(Backoff.jitteredBy(Backoff.fibonacciSeconds(), () -> 7)).with(waits)).answer(ask());

        assertEquals(List.of(8L, 8L, 9L), waits.asked,
                "1+7, 1+7, 2+7 — the draw is added to the schedule, not substituted for it");
    }

    @Test
    void theWholeSequenceStopsAtTheBudgetHoweverManyAttemptsAreLeft() {
        Flaky endpoint = new Flaky(Integer.MAX_VALUE);
        // Eleven minutes pass every time the clock is read, standing in for an endpoint that
        // freezes and costs a full stall per attempt. The thirty-minute budget is then spent long
        // before the ten attempts are — and this is the shipped helper, so a consumer writes the
        // same test the same way.
        Retry frozenEndpoint = plain(new Waits()).with(Now.steppingBy(Duration.ofMinutes(11)));

        assertThrows(RuntimeException.class,
                () -> Model.wrap(endpoint, new Notes(), frozenEndpoint).answer(ask()));

        assertEquals(3, endpoint.calls.get(),
                "three attempts and not ten: a frozen endpoint must not cost ten stalls");
    }

    /** The shipped policy with the jitter draw pinned to zero and the waiting recorded. */
    private static Retry plain(Pause pause) {
        return Retry.fromEnv()
                .with(Backoff.jitteredBy(Backoff.fibonacciSeconds(), () -> 0))
                .with(pause);
    }

    /** A clock that does not move, so the budget never fires in the tests that are not about it. */
    private static final Now FROZEN = Now.frozenAt(0);

    /** Every wrap in this file freezes the clock; the budget has its own test. */
    private static Chat wrapped(Chat client, Trace t, Retry r) {
        return Model.wrap(client, t, r.with(FROZEN));
    }

    /** Records what it was asked to wait for, and returns at once. */
    private static final class Waits implements Pause {
        final List<Long> asked = new ArrayList<>();

        @Override
        public void of(java.time.Duration wait) {
            asked.add(wait.toSeconds());
        }
    }

    // ---------------------------------------------------------------- the fakes

    private static Ask ask() {
        return Ask.of(List.of(Said.user("what is the ratchet")));
    }

    /**
     * An endpoint that drops its first {@code drops} calls, then answers.
     *
     * <p>One method, because {@link Chat} is one method. It used to implement a streaming client
     * and report its failure through a handler callback, which is the shape the deleted adapter
     * needed; the adapter is gone and the failure is simply thrown, exactly as {@link Wire} throws
     * it.
     */
    private static final class Flaky implements Chat {
        final AtomicInteger calls = new AtomicInteger();
        private final int drops;
        private final java.util.function.Function<String, RuntimeException> failure;
        private final String because;

        Flaky(int drops) {
            // What Wire raises when the socket dies: the IOException wrapped, because Chat is a
            // functional interface and a checked throw would put a try/catch in every fake.
            this(drops, dropped -> new IllegalStateException(
                    "could not reach the endpoint: " + dropped, new IOException(dropped)),
                    "connection reset");
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

    /** A trace that keeps the notes the retry writes. */
    private static final class Notes implements Trace {
        final List<String> progress = new ArrayList<>();

        public void asked(String agent, String prompt, String reply) {
        }

        public void progress(String key, String note) {
            progress.add(note);
        }

        public void applied(String stage, String what) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String f, String t, String c) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String k, String s, String w, boolean before, boolean after) {
        }

        public void failed(String k, Throwable c) {
        }

        public void priced(String k, String m, String i) {
        }
    }
}
