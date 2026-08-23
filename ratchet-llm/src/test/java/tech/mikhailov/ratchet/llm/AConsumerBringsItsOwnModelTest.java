package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE RETRY WITHOUT THE CLIENT, WHICH IS WHAT TWO CONSUMERS ASKED FOR.
 *
 * <p>ratchet#2 and ratchet#5, filed separately by two repositories, wanted the same door.
 * {@link Model} builds the client itself — endpoint at process start, its own listeners, its own
 * silence ceiling, temperature pinned — and a consumer that resolves its endpoint from a settings
 * file per call, or sets a temperature per stage, could not take it. Adopting {@code Model} to reach
 * the retry meant rewriting everything around the one part they wanted.
 *
 * <p>ratchet#5 put it in a sentence there is no answer to: <em>"We do not want to write this; we
 * want to call it."</em>
 */
class AConsumerBringsItsOwnModelTest {

    @Test
    void aModelTheConsumerBuiltCanBeWrappedInTheShippedRetry() {
        Flaky theirs = new Flaky(2);
        Waits waits = new Waits();

        ChatModel retried = Retrying.on(theirs,
                Retry.fibonacciSeconds(10, 0, Duration.ofMinutes(30)).with(waits).with(FROZEN),
                QUIET);

        assertEquals("answer 3", retried.chat(ask()).aiMessage().text());
        assertEquals(3, theirs.calls.get(), "their model, our schedule");
        assertEquals(List.of(1L, 1L), waits.asked, "and it is the Fibonacci one");
    }

    @Test
    void thePredicateIsReachableOnItsOwn() {
        // ratchet#2: "that judgement is the valuable half and is harder to get right than the loop
        // around it. If only one thing is made public, that predicate is more useful than the wrapper."
        assertTrue(Retrying.transportFailures().test(new IllegalStateException("connection reset")));
        assertFalse(Retrying.transportFailures().test(new AuthenticationException("bad key")));
        assertFalse(Retrying.transportFailures().test(new Streamed.Truncated("no room")));
        assertFalse(Retrying.transportFailures().test(new Streamed.GaveUp("3h")));
    }

    @Test
    void theLivenessGuardIsReachableWithoutTheClientToo() {
        // ratchet#5 has its own silence ceiling and did not want Model's; a consumer that would
        // rather have this one should be able to take it without taking everything around it.
        ChatModel guarded = Streamed.over(ended(FinishReason.STOP, "said something"), QUIET);

        assertEquals("said something", guarded.chat(ask()).aiMessage().text());
    }

    @Test
    void aConsumerCanRefuseTheTruncationItselfBecauseTheTypeIsPublic() {
        ChatModel guarded = Streamed.over(ended(FinishReason.LENGTH, ""), QUIET);

        Streamed.Truncated cut = assertThrows(Streamed.Truncated.class, () -> guarded.chat(ask()));
        assertTrue(cut.getMessage().contains("token limit"), cut.getMessage());
    }

    @Test
    void temperatureIsTheConsumersToChooseNow() {
        // ratchet#4: unsloth's guidance for Qwen 3.8 is that reasoning_effort requires 1.0, and a
        // consumer running that model could not comply with a hardcoded zero.
        assertEquals(0.0, Sampling.deterministic().temperature(),
                "zero stays the default, and the reason for it stays true");
        assertEquals(1.0, Sampling.asTheModelRequires(1.0).temperature());
        assertEquals(0.7, Sampling.deterministic().withTemperature(0.7).temperature());
    }

    @Test
    void theChosenTemperatureReachesTheClientAndNotJustTheRecord() {
        // THE SEAM, NOT THE VALUE. An earlier version of this file asserted only that Sampling
        // HELD 1.0, and hardcoding temperature(0.0) back into build() turned no test red — the
        // record was right and nothing proved the client was told. Building never opens a socket,
        // so the assembled chain can be asked what it would send.
        ChatModel built = Model.forProducer(QUIET,
                Endpoint.of("http://127.0.0.1:1/v1", "some-model"),
                Retry.none(), Sampling.asTheModelRequires(1.0));

        assertEquals(1.0, built.defaultRequestParameters().temperature(),
                "the model a consumer gets must answer at the temperature it asked for");
    }

    @Test
    void theChosenCompletionBudgetReachesTheClientToo() {
        // Found by the same mutation one field over: temperature was proved at the seam and
        // maxTokens was not, so hardcoding it back turned nothing red. Both halves of Sampling
        // that go to the builder are asserted where they land, not where they are stored.
        ChatModel built = Model.forProducer(QUIET,
                Endpoint.of("http://127.0.0.1:1/v1", "some-model"),
                Retry.none(), Sampling.deterministic().withThinkingTokens(512).withMaxTokens(2_048));

        assertEquals(2_048, built.defaultRequestParameters().maxOutputTokens());
        // The thinking budget had to come down first, because the invariant refuses a reasoning
        // budget that swallows the pool — it caught this test before the test caught anything.
    }

    @Test
    void theShippedDefaultIsStillZeroAllTheWayDown() {
        ChatModel built = Model.forProducer(QUIET,
                Endpoint.of("http://127.0.0.1:1/v1", "some-model"),
                Retry.none(), Sampling.deterministic());

        assertEquals(0.0, built.defaultRequestParameters().temperature(),
                "most replies here are branched on, and that reason has not changed");
    }

    @Test
    void theTwoBudgetsAreSeparateNumbersEvenThoughTheServerPoolsThem() {
        // Measured: thinkingTokens is the lever that decides whether there is an answer at all.
        // 11,700 characters of reasoning and none left for a reply, against 233 and a full answer.
        Sampling roomToAnswer = Sampling.deterministic().withThinkingTokens(500);

        assertEquals(500, roomToAnswer.thinkingTokens());
        assertEquals(16_000, roomToAnswer.maxTokens());
        assertEquals(500, Model.extras(true, roomToAnswer).get("thinking_token_budget"),
                "and it reaches the request rather than staying a field");
    }

    @Test
    void aReasoningBudgetThatLeavesNoRoomForAnAnswerIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Sampling.deterministic().withThinkingTokens(16_000),
                "they share a pool, so a budget equal to the whole of it guarantees the empty answer");
        assertThrows(IllegalArgumentException.class,
                () -> new Sampling(-1, 16_000, 4_000), "and a negative temperature is not a choice");
    }

    @Test
    void thinkingOffIsSaidByTheSamplingRatherThanOnlyByTheEnvironment() {
        assertFalse(Sampling.deterministic().withThinkingTokens(0).thinks());
        assertFalse(Model.extras(true, Sampling.deterministic().withThinkingTokens(0))
                .containsKey("thinking_token_budget"),
                "no budget field at all when there is no thinking to bound");
    }

    // ---------------------------------------------------------------- the fakes

    private static final Now FROZEN = Now.frozenAt(0);

    private static ChatRequest ask() {
        return ChatRequest.builder().messages(UserMessage.from("who speaks first?")).build();
    }

    private static StreamingChatModel ended(FinishReason why, String content) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(content))
                        .metadata(ChatResponseMetadata.builder().finishReason(why).build())
                        .build());
            }
        };
    }

    /** A consumer's own ChatModel — built however they like, dropping its first calls. */
    private static final class Flaky implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        private final int drops;

        Flaky(int drops) {
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

    private static final class Waits implements Pause {
        final List<Long> asked = new java.util.ArrayList<>();

        @Override
        public void of(Duration wait) {
            asked.add(wait.toSeconds());
        }
    }

    private static final Trace QUIET = new Trace() {
        public void asked(String a, String p, String r) { }
        public void progress(String k, String n) { }
        public void applied(String s, String w) { }
        public void tool(String a, String t, String g, String r) { }
        public void thought(String f, String t, String c) { }
        public void built(String p, Trace.Outcome r) { }
        public void settled(String k, String s, String w, boolean b, boolean a) { }
        public void failed(String k, Throwable c) { }
        public void priced(String k, String m, String i) { }
    };
}
