package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE DECORATORS ARE PROVED TO BE INSTALLED, AND NOT ONLY TO WORK.
 *
 * <p>{@link Retrying} had seventeen tests and none of them touched {@link Model}. The whole
 * decorator could be deleted from the chain and every one of those tests still passed, because
 * {@code build} cannot be called without a live base URL and so nothing ever asserted what it
 * assembles. A guard that is correct and not connected is not a guard.
 *
 * <p>This asks the question the other file cannot: given the chain {@code Model} actually assembles,
 * does a dropped connection get asked again? It runs through the real {@link Streamed}, the real
 * predicate and the real schedule. Only the three things that take wall-clock time — the jitter
 * draw, the wait and the clock — are handed in, because the production draw is up to a minute wide
 * and a test that lived through one would be a test somebody eventually deletes.
 */
class TheChainModelBuildsReallyRetriesTest {

    @Test
    void aDroppedConnectionIsAskedAgainByTheChainModelBuilds() {
        Flaky endpoint = new Flaky(1);
        Notes notes = new Notes();

        Waits waits = new Waits();
        ChatResponse answer = Model.wrap(endpoint, notes, plain(waits), CLOCK).chat(ask());

        assertEquals("answer 2", answer.aiMessage().text(),
                "the retry's answer, through the chain build() assembles");
        assertEquals(2, endpoint.calls.get(), "the endpoint really was asked twice");
        assertEquals(List.of(1L), waits.asked,
                "and the wait is the first Fibonacci second, through the production schedule");
    }

    @Test
    void aRefusedRequestIsNotRetriedByTheChainEither() {
        Flaky endpoint = new Flaky(Integer.MAX_VALUE, AuthenticationException::new, "bad key");
        Notes notes = new Notes();

        assertThrows(RuntimeException.class,
                () -> Model.wrap(endpoint, notes, plain(new Waits()), CLOCK).chat(ask()));

        assertEquals(1, endpoint.calls.get(),
                "the production predicate is wired in, not just the production count");
    }

    @Test
    void theDefaultIsTenAttempts() {
        assertEquals(10, Model.attemptsFrom("10"));
        assertEquals(10, Model.attempts(), "unset in this JVM, so the documented default is in force");
    }

    @Test
    void aValueNobodyCanReadFallsBackInsteadOfKillingTheProcess() {
        assertEquals(10, Model.attemptsFrom("ten"), "a typo must not take the sweep down");
        assertEquals(10, Model.attemptsFrom(""), "nor an empty one");
        assertEquals(3, Model.attemptsFrom("  3  "), "and whitespace is not a typo");
    }

    @Test
    void oneIsAValidAnswerAndMeansTheBehaviourFromBefore() {
        assertEquals(1, Model.attemptsFrom("1"));
        assertEquals(1, Model.attemptsFrom("0"), "and nothing below one, which would ask zero times");
        assertEquals(1, Model.attemptsFrom("-4"));
    }

    @Test
    void theProductionScheduleCarriesAJitterDrawOnTopOfEachFibonacciSecond() {
        Flaky endpoint = new Flaky(3);
        Waits waits = new Waits();

        Model.wrap(endpoint, new Notes(), Retry.fromEnv().with(Backoff.jitteredBy(Backoff.fibonacciSeconds(), () -> 7)).with(waits), CLOCK).chat(ask());

        assertEquals(List.of(8L, 8L, 9L), waits.asked,
                "1+7, 1+7, 2+7 — the draw is added to the schedule, not substituted for it");
    }

    @Test
    void theWholeSequenceStopsAtTheBudgetHoweverManyAttemptsAreLeft() {
        Flaky endpoint = new Flaky(Integer.MAX_VALUE);
        // A clock that jumps eleven minutes every time it is read, standing in for an endpoint that
        // freezes and costs a full stall per attempt. The thirty-minute budget is then spent long
        // before the ten attempts are.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        java.util.function.LongSupplier frozen = () -> clock.getAndAdd(Duration.ofMinutes(11).toMillis());

        assertThrows(RuntimeException.class, () -> Model.wrap(endpoint, new Notes(), plain(new Waits()), frozen).chat(ask()));

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
    private static final java.util.function.LongSupplier CLOCK = () -> 0L;

    /** Records what it was asked to wait for, and returns at once. */
    private static final class Waits implements Pause {
        final List<Long> asked = new ArrayList<>();

        @Override
        public void of(java.time.Duration wait) {
            asked.add(wait.toSeconds());
        }
    }

    // ---------------------------------------------------------------- the fakes

    private static ChatRequest ask() {
        return ChatRequest.builder().messages(UserMessage.from("what is the ratchet")).build();
    }

    /** A streaming endpoint that drops its first {@code drops} calls, then answers. */
    private static final class Flaky implements StreamingChatModel {
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
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            int call = calls.incrementAndGet();
            if (call <= drops) {
                handler.onError(failure.apply(because));
                return;
            }
            handler.onPartialResponse("ans");
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from("answer " + call)).build());
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
