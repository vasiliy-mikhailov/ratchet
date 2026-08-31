package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DROPPED CONNECTION COSTS A STAGE, AND A STAGE IS EVERY CALL THAT STAGE ALREADY MADE.
 *
 * <p>The journal writes its row only once a wrapped node returns, and nothing inside a node is
 * preserved — not a triad's plan, not a round that finished, not a verdict. Measured on this
 * library: a verifier that died on round three of a six-round budget destroyed seven paid model
 * calls and left no journal file at all. Against that, asking the endpoint again is nearly free,
 * and these are the rules for when it happens.
 *
 * <p>Every wait is asserted through a {@link Pause} that records what it was asked for and returns
 * at once, so the full ten-attempt schedule is checked here in milliseconds rather than the
 * eighty-eight seconds it really spends.
 *
 * <p>THE FAKE ENDPOINT IS NOW A {@link Chat}, which is one method, and that is not a cosmetic
 * difference. The fakes below used to implement a third party's five-method interface, so the
 * failures they could stage were the failures that library had types for, and the classification
 * under test had to be written against those types. It is written against {@link Refused} now — a
 * status this library read off the response itself — and every case here is a value a test can
 * construct.
 */
class AFlakyEndpointIsAskedAgainTest {

    @Test
    void aCallThatWorksIsNotRetriedAndNeverWaits() {
        Endpoint endpoint = new Endpoint(0);
        Waits waits = new Waits();

        Reply answer = retrying(endpoint, 10, waits, new Notes()).answer(ask());

        assertEquals("answer 1", answer.said());
        assertEquals(1, endpoint.calls.get(), "asked once");
        assertEquals(List.of(), waits.asked, "and never slept");
    }

    @Test
    void aBlipIsAskedAgainAndTheAnswerIsTheSecondOne() {
        Endpoint endpoint = new Endpoint(1);
        Waits waits = new Waits();

        Reply answer = retrying(endpoint, 10, waits, new Notes()).answer(ask());

        assertEquals("answer 2", answer.said(), "the retry's answer is the answer");
        assertEquals(2, endpoint.calls.get());
        assertEquals(List.of(1L), waits.asked, "one wait, of one second");
    }

    @Test
    void tenAttemptsAreMadeAndTheNineWaitsAreFibonacci() {
        Endpoint endpoint = new Endpoint(Integer.MAX_VALUE);
        Waits waits = new Waits();

        IllegalStateException surfaced = assertThrows(IllegalStateException.class,
                () -> retrying(endpoint, 10, waits, new Notes()).answer(ask()));

        assertEquals(10, endpoint.calls.get(), "ten attempts, not eleven and not nine");
        assertEquals(List.of(1L, 1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L), waits.asked,
                "nine waits between ten attempts, in Fibonacci seconds");
        assertTrue(surfaced.getMessage().contains("connection reset"),
                "and the failure that surfaces is the endpoint's own, not a wrapper's: " + surfaced);
    }

    @Test
    void theLastAttemptDoesNotSleepBeforeGivingUp() {
        Endpoint endpoint = new Endpoint(Integer.MAX_VALUE);
        Waits waits = new Waits();

        assertThrows(IllegalStateException.class,
                () -> retrying(endpoint, 3, waits, new Notes()).answer(ask()));

        assertEquals(3, endpoint.calls.get());
        assertEquals(2, waits.asked.size(),
                "two waits for three attempts — sleeping after the last would delay the failure");
    }

    @Test
    void aCeilingIsNotRetriedBecauseTheLaneIsBeingGivenBackOnPurpose() {
        Thrower endpoint = new Thrower(
                () -> new GaveUp("still streaming after 3h; giving the lane back"));
        Waits waits = new Waits();

        assertThrows(GaveUp.class,
                () -> retrying(endpoint, 10, waits, new Notes()).answer(ask()));

        assertEquals(1, endpoint.calls.get(), "asked once and not again");
        assertEquals(List.of(), waits.asked, "and never slept");
    }

    @Test
    void aRefusedRequestIsNotRetriedNineMoreTimes() {
        Thrower endpoint = new Thrower(() -> new Refused(401, "bad key"));
        Waits waits = new Waits();

        assertThrows(Refused.class,
                () -> retrying(endpoint, 10, waits, new Notes()).answer(ask()));

        assertEquals(1, endpoint.calls.get(),
                "nine more attempts cannot fix a credential, and each re-prefills the conversation");
    }

    @Test
    void aRateLimitIsRetriedAndAnInvalidRequestIsNot() {
        // The two sit either side of the line, and the line is a number this library read off the
        // response rather than a sentence it went looking through. There used to be two checks
        // here — a client's typed hierarchy AND a raw status — because the mapping between them ran
        // in somebody else's internal package with no promise it had run at all for a STREAMING
        // failure. One field settles it now.
        assertTrue(Retrying.transportFailures().test(new Refused(429, "slow down")));
        assertFalse(Retrying.transportFailures().test(new Refused(400, "no such field")));
        assertTrue(Retrying.transportFailures().test(new Refused(503, "unavailable")),
                "a 5xx is the server failing, and that is still worth another request");
        assertFalse(Retrying.transportFailures().test(new Refused(404, "no such model")),
                "and a 404 still is not");
        assertTrue(Retrying.transportFailures().test(new Refused(408, "request timeout")),
                "429 and 408 are the server saying not now, not that the request is wrong");
    }

    @Test
    void theOutermostClassificationWinsOverWhateverItWraps() {
        // What the early return in the walk is actually FOR. It answers on the FIRST classified
        // failure in the chain, and without that it would run on to the cause and answer with
        // whichever refusal happened to be innermost. "The endpoint refused this request with a
        // 503" and "a 401 is buried somewhere underneath" are different facts, and only the outer
        // one is about the request that was just made.
        //
        // The cause is attached with initCause because Refused takes a status and a body and no
        // cause. That is deliberate — the status is what it read off the response — so if it ever
        // gains a cause-taking constructor AND uses it, initCause here will throw and this test
        // will fail for a reason that has nothing to do with what it asserts. Attach the cause the
        // new constructor's way at that point; do not weaken the chain to two unrelated failures.
        Refused retriableAroundPermanent = new Refused(503, "unavailable");
        retriableAroundPermanent.initCause(new Refused(401, "stale token"));
        assertTrue(Retrying.transportFailures().test(retriableAroundPermanent),
                "the status on the failure itself decides; a cause underneath does not overrule it");

        // And the mirror: permanent on the outside is permanent, whatever it wraps.
        Refused permanentAroundRetriable = new Refused(401, "bad key");
        permanentAroundRetriable.initCause(new Refused(429, "slow down"));
        assertFalse(Retrying.transportFailures().test(permanentAroundRetriable));
    }

    @Test
    void aMessageThatMerelyQuotesAStatusIsNotAClassification() {
        // The bug this replaced: a 500 whose body quoted a 401 was read as permanent and never
        // retried, and a refusal phrased any other way was retried nine times. Reading the status
        // off the response instead of out of the prose is the whole of the fix — and note that a
        // Refused is not what arrives here, so nothing in this sentence is classified at all.
        assertTrue(Retrying.transportFailures()
                        .test(new IllegalStateException("upstream returned 500: token 401 rejected")),
                "prose is not a status; an unrecognised failure is retried");
    }

    @Test
    void aTruncationAndARunawayAreNotAskedAgainEither() {
        // BOTH OF THESE ONLY REACH THIS CLASS NOW. Truncation was an empty answer the runtime
        // returned as the agent's reply; the loop detection was thrown out of an SSE listener and
        // swallowed there by the client, so the retry never had to have an opinion about it. The
        // opinion is no, in both cases, and for the same arithmetic: the identical request meets
        // the identical budget, so ten attempts buy ten more full generations. Measured for the
        // runaway — 14 trapped generations restarted with 2,500 more tokens each, none escaped.
        Thrower truncated = new Thrower(
                () -> new Truncated("the model reached the token limit before writing an answer"));
        assertThrows(Truncated.class,
                () -> retrying(truncated, 10, new Waits(), new Notes()).answer(ask()));
        assertEquals(1, truncated.calls.get(), "one full generation was enough to learn this");

        Thrower runaway = new Thrower(
                () -> new Reasoning.LoopDetected("the same substantial line, again", 6));
        assertThrows(Reasoning.LoopDetected.class,
                () -> retrying(runaway, 10, new Waits(), new Notes()).answer(ask()));
        assertEquals(1, runaway.calls.get(),
                "greedy decoding cannot leave a cycle it has entered; Insisting re-asks without it");
    }

    @Test
    void aStallIsRetriedBecauseAFreshConnectionIsExactlyWhatItNeeds() {
        // The exact sentence Wire throws when no token has arrived for the stall window. It is a
        // plain IllegalStateException on purpose: a connection that stopped producing is the one
        // thing a second connection genuinely fixes, so it must fall through to the retry.
        Thrower endpoint = new Thrower(
                () -> new IllegalStateException("no token for 20 minutes: the connection is not producing"));
        Waits waits = new Waits();

        assertThrows(IllegalStateException.class,
                () -> retrying(endpoint, 4, waits, new Notes()).answer(ask()));

        assertEquals(4, endpoint.calls.get(), "the stall is the case retrying is for");
    }

    @Test
    void everyRetryIsInTheRecord() {
        Endpoint endpoint = new Endpoint(2);
        Notes notes = new Notes();

        retrying(endpoint, 10, new Waits(), notes).answer(ask());

        assertEquals(2, notes.progress.size(), "one note per retry, including the run that recovered");
        assertTrue(notes.progress.get(0).contains("attempt 1 of 10"), notes.progress.get(0));
        assertTrue(notes.progress.get(0).contains("asking again in 1s"), notes.progress.get(0));
        assertTrue(notes.progress.get(1).contains("attempt 2 of 10"), notes.progress.get(1));
    }

    @Test
    void anInterruptedWaitStopsRatherThanFinishingTheSchedule() {
        Endpoint endpoint = new Endpoint(Integer.MAX_VALUE);
        Pause interrupted = wait -> {
            throw new InterruptedException("stopping");
        };

        assertThrows(IllegalStateException.class, () -> new Retrying(endpoint, 10,
                Duration.ofMinutes(30), Backoff.fibonacciSeconds(), interrupted,
                Retrying.transportFailures(), FROZEN, new Notes()).answer(ask()));

        assertEquals(1, endpoint.calls.get(), "a lane being stopped must stop, not serve out 88s");
        assertTrue(Thread.interrupted(), "and the interrupt is put back for whatever checks it next");
    }

    @Test
    void aTraceThatThrowsDoesNotBreakACallThatWouldHaveWorked() {
        Endpoint endpoint = new Endpoint(1);
        Trace broken = new Notes() {
            @Override
            public void progress(String key, String note) {
                throw new IllegalStateException("the record is unwritable");
            }
        };

        Reply answer = retrying(endpoint, 10, new Waits(), broken).answer(ask());

        assertEquals("answer 2", answer.said(), "recording must not break the run");
    }

    @Test
    void oneAttemptMeansTheBehaviourFromBeforeThisExisted() {
        Endpoint endpoint = new Endpoint(1);
        Waits waits = new Waits();

        assertThrows(IllegalStateException.class,
                () -> retrying(endpoint, 1, waits, new Notes()).answer(ask()));

        assertEquals(1, endpoint.calls.get());
        assertEquals(List.of(), waits.asked);
    }

    @Test
    void aSurvivingFailureIsTheOriginalObjectAndNotACopy() {
        RuntimeException dropped = new IllegalStateException("connection reset");
        Thrower endpoint = new Thrower(() -> dropped);

        RuntimeException surfaced = assertThrows(RuntimeException.class,
                () -> retrying(endpoint, 2, new Waits(), new Notes()).answer(ask()));

        assertSame(dropped, surfaced, "the caller sees what the transport threw, with its stack");
        assertNotEquals(0, endpoint.calls.get());
    }

    @Test
    void theScheduleIsHandedEveryFailureSoFarAndNotJustTheLast() {
        Endpoint endpoint = new Endpoint(3);
        List<List<String>> seen = new ArrayList<>();
        Backoff watching = failed -> {
            seen.add(failed.stream().map(Throwable::getMessage).toList());
            return Duration.ZERO;
        };

        new Retrying(endpoint, 10, Duration.ofMinutes(30), watching, new Waits(),
                Retrying.transportFailures(), FROZEN, new Notes()).answer(ask());

        assertEquals(3, seen.size(), "consulted once before each retry");
        assertEquals(List.of(1, 2, 3), seen.stream().map(List::size).toList(),
                "and the history grows: one failure, then two, then three");
        assertEquals(List.of("connection reset", "connection reset", "connection reset"),
                seen.get(2), "the whole history, oldest first, not the last one repeated");
    }

    @Test
    void theHistoryHandedOutCannotBeChangedFromOutside() {
        Endpoint endpoint = new Endpoint(2);
        Backoff meddling = failed -> {
            assertThrows(UnsupportedOperationException.class, () -> failed.add(new RuntimeException("mine")),
                    "a schedule must not be able to rewrite the run's own record of what happened");
            return Duration.ZERO;
        };

        new Retrying(endpoint, 10, Duration.ofMinutes(30), meddling, new Waits(),
                Retrying.transportFailures(), FROZEN, new Notes()).answer(ask());

        assertEquals(3, endpoint.calls.get());
    }

    /** A clock that does not move, so the budget never fires in the tests that are not about it. */
    private static final Now FROZEN = Now.frozenAt(0);

    // ---------------------------------------------------------------- the fakes

    private static Chat retrying(Chat inner, int attempts, Pause pause, Trace trace) {
        return new Retrying(inner, attempts, Duration.ofMinutes(30), Backoff.fibonacciSeconds(),
                pause, Retrying.transportFailures(), FROZEN, trace);
    }

    private static Ask ask() {
        return Ask.of(List.of(Said.user("what is the ratchet")));
    }

    /** Fails its first {@code drops} calls with a dropped connection, then answers. */
    private static final class Endpoint implements Chat {
        final AtomicInteger calls = new AtomicInteger();
        private final int drops;

        Endpoint(int drops) {
            this.drops = drops;
        }

        @Override
        public Reply answer(Ask ask) {
            int call = calls.incrementAndGet();
            if (call <= drops) {
                throw new IllegalStateException("connection reset");
            }
            return new Reply("answer " + call, "", List.of(), Ending.STOPPED, Spend.NONE);
        }
    }

    /** Always throws the same kind of failure, so the predicate is what is under test. */
    private static final class Thrower implements Chat {
        final AtomicInteger calls = new AtomicInteger();
        private final java.util.function.Supplier<RuntimeException> failure;

        Thrower(java.util.function.Supplier<RuntimeException> failure) {
            this.failure = failure;
        }

        @Override
        public Reply answer(Ask ask) {
            calls.incrementAndGet();
            throw failure.get();
        }
    }

    /** Records what it was asked to wait for, and returns at once. */
    private static final class Waits implements Pause {
        final List<Long> asked = new ArrayList<>();

        @Override
        public void of(Duration wait) {
            asked.add(wait.toSeconds());
        }
    }

    /** A trace that keeps the notes this decorator is judged on. */
    private static class Notes implements Trace {
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
