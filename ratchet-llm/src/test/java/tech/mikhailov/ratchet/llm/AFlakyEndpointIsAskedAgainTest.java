package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 */
class AFlakyEndpointIsAskedAgainTest {

    @Test
    void aCallThatWorksIsNotRetriedAndNeverWaits() {
        Endpoint endpoint = new Endpoint(0);
        Waits waits = new Waits();

        ChatResponse answer = retrying(endpoint, 10, waits, new Notes()).chat(ask());

        assertEquals("answer 1", text(answer));
        assertEquals(1, endpoint.calls.get(), "asked once");
        assertEquals(List.of(), waits.asked, "and never slept");
    }

    @Test
    void aBlipIsAskedAgainAndTheAnswerIsTheSecondOne() {
        Endpoint endpoint = new Endpoint(1);
        Waits waits = new Waits();

        ChatResponse answer = retrying(endpoint, 10, waits, new Notes()).chat(ask());

        assertEquals("answer 2", text(answer), "the retry's answer is the answer");
        assertEquals(2, endpoint.calls.get());
        assertEquals(List.of(1L), waits.asked, "one wait, of one second");
    }

    @Test
    void tenAttemptsAreMadeAndTheNineWaitsAreFibonacci() {
        Endpoint endpoint = new Endpoint(Integer.MAX_VALUE);
        Waits waits = new Waits();

        IllegalStateException gaveUp = assertThrows(IllegalStateException.class,
                () -> retrying(endpoint, 10, waits, new Notes()).chat(ask()));

        assertEquals(10, endpoint.calls.get(), "ten attempts, not eleven and not nine");
        assertEquals(List.of(1L, 1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L), waits.asked,
                "nine waits between ten attempts, in Fibonacci seconds");
        assertTrue(gaveUp.getMessage().contains("connection reset"),
                "and the failure that surfaces is the endpoint's own, not a wrapper's: " + gaveUp);
    }

    @Test
    void theLastAttemptDoesNotSleepBeforeGivingUp() {
        Endpoint endpoint = new Endpoint(Integer.MAX_VALUE);
        Waits waits = new Waits();

        assertThrows(IllegalStateException.class,
                () -> retrying(endpoint, 3, waits, new Notes()).chat(ask()));

        assertEquals(3, endpoint.calls.get());
        assertEquals(2, waits.asked.size(),
                "two waits for three attempts — sleeping after the last would delay the failure");
    }

    @Test
    void aCeilingIsNotRetriedBecauseTheLaneIsBeingGivenBackOnPurpose() {
        Thrower endpoint = new Thrower(() -> new Streamed.GaveUp("still streaming after 3h"));
        Waits waits = new Waits();

        assertThrows(Streamed.GaveUp.class,
                () -> retrying(endpoint, 10, waits, new Notes()).chat(ask()));

        assertEquals(1, endpoint.calls.get(), "asked once and not again");
        assertEquals(List.of(), waits.asked, "and never slept");
    }

    @Test
    void aRefusedRequestIsNotRetriedNineMoreTimes() {
        Thrower endpoint = new Thrower(() -> new IllegalStateException("status code: 401, body: bad key"));
        Waits waits = new Waits();

        assertThrows(IllegalStateException.class,
                () -> retrying(endpoint, 10, waits, new Notes()).chat(ask()));

        assertEquals(1, endpoint.calls.get(),
                "nine more attempts cannot fix a credential, and each re-prefills the conversation");
    }

    @Test
    void aStallIsRetriedBecauseAFreshConnectionIsExactlyWhatItNeeds() {
        Thrower endpoint = new Thrower(
                () -> new IllegalStateException("no token for 20 minutes: the connection is not producing"));
        Waits waits = new Waits();

        assertThrows(IllegalStateException.class,
                () -> retrying(endpoint, 4, waits, new Notes()).chat(ask()));

        assertEquals(4, endpoint.calls.get(), "the stall is the case retrying is for");
    }

    @Test
    void everyRetryIsInTheRecord() {
        Endpoint endpoint = new Endpoint(2);
        Notes notes = new Notes();

        retrying(endpoint, 10, new Waits(), notes).chat(ask());

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
                Retrying.transportFailures(), CLOCK, new Notes()).chat(ask()));

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

        ChatResponse answer = retrying(endpoint, 10, new Waits(), broken).chat(ask());

        assertEquals("answer 2", text(answer), "recording must not break the run");
    }

    @Test
    void oneAttemptMeansTheBehaviourFromBeforeThisExisted() {
        Endpoint endpoint = new Endpoint(1);
        Waits waits = new Waits();

        assertThrows(IllegalStateException.class,
                () -> retrying(endpoint, 1, waits, new Notes()).chat(ask()));

        assertEquals(1, endpoint.calls.get());
        assertEquals(List.of(), waits.asked);
    }

    @Test
    void aSurvivingFailureIsTheOriginalObjectAndNotACopy() {
        RuntimeException dropped = new IllegalStateException("connection reset");
        Thrower endpoint = new Thrower(() -> dropped);

        RuntimeException surfaced = assertThrows(RuntimeException.class,
                () -> retrying(endpoint, 2, new Waits(), new Notes()).chat(ask()));

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
                Retrying.transportFailures(), CLOCK, new Notes()).chat(ask());

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
                Retrying.transportFailures(), CLOCK, new Notes()).chat(ask());

        assertEquals(3, endpoint.calls.get());
    }

    /** A clock that does not move, so the budget never fires in the tests that are not about it. */
    private static final java.util.function.LongSupplier CLOCK = () -> 0L;

    // ---------------------------------------------------------------- the fakes

    private static ChatModel retrying(ChatModel inner, int attempts, Pause pause, Trace trace) {
        return new Retrying(inner, attempts, Duration.ofMinutes(30), Backoff.fibonacciSeconds(),
                pause, Retrying.transportFailures(), CLOCK, trace);
    }

    private static ChatRequest ask() {
        return ChatRequest.builder().messages(UserMessage.from("what is the ratchet")).build();
    }

    private static String text(ChatResponse response) {
        return response.aiMessage().text();
    }

    /** Fails its first {@code drops} calls with a dropped connection, then answers. */
    private static class Endpoint implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        private final int drops;

        Endpoint(int drops) {
            this.drops = drops;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return doChat(request);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            int call = calls.incrementAndGet();
            if (call <= drops) {
                throw new IllegalStateException("connection reset");
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("answer " + call)).build();
        }
    }

    /** Always throws the same kind of failure, so the predicate is what is under test. */
    private static final class Thrower implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        private final java.util.function.Supplier<RuntimeException> failure;

        Thrower(java.util.function.Supplier<RuntimeException> failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return doChat(request);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
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
