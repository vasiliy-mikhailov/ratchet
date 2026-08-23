package tech.mikhailov.ratchet.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
 *
 * <p>THE DOOR IS WIDER NOW THAN IT WAS WHEN THEY ASKED. ratchet#2's other half was arithmetic:
 * eight megabytes of model client and thirteen jars, an NLP toolkit and a BPE tokenizer among them,
 * arriving with a function over a list of failures. {@link Chat} is one method and this library
 * owns its transport, so the model a consumer hands in is a lambda — the {@code Flaky} at the
 * bottom of this file used to need two methods to say the same thing, because the interface it
 * implemented carried five and a fake had to override the one that delegated as well as the one
 * that answered.
 */
class AConsumerBringsItsOwnModelTest {

    @Test
    void aModelTheConsumerBuiltCanBeWrappedInTheShippedRetry() {
        Flaky theirs = new Flaky(2);
        Waits waits = new Waits();

        Chat retried = Retrying.on(theirs,
                Retry.fibonacciSeconds(10, 0, Duration.ofMinutes(30)).with(waits).with(FROZEN),
                QUIET);

        assertEquals("answer 3", retried.answer(ask()).said());
        assertEquals(3, theirs.calls.get(), "their model, our schedule");
        assertEquals(List.of(1L, 1L), waits.asked, "and it is the Fibonacci one");
    }

    @Test
    void thePredicateIsReachableOnItsOwn() {
        // ratchet#2: "that judgement is the valuable half and is harder to get right than the loop
        // around it. If only one thing is made public, that predicate is more useful than the wrapper."
        assertTrue(Retrying.transportFailures().test(new IllegalStateException("connection reset")));
        // A bad key used to arrive as a client library's typed exception, mapped from the status in
        // an internal package with no promise the mapping had run. It is the status now, read off
        // the response by a client this library owns, and 401 is not worth a second request.
        assertFalse(Retrying.transportFailures().test(new Refused(401, "invalid api key")));
        assertFalse(Retrying.transportFailures().test(new Truncated("no room")));
        assertFalse(Retrying.transportFailures().test(new GaveUp("3h")));
        // NEW, AND NEW BECAUSE IT USED TO BE IMPOSSIBLE. The runaway detection was thrown out of an
        // SSE listener and the client swallowed it there, so this predicate never saw one and could
        // not have an opinion. It has one now, and it is no: greedy decoding cannot leave a cycle
        // it has entered, and one experiment restarted 14 trapped generations with 2,500 more
        // tokens each — none escaped.
        assertFalse(Retrying.transportFailures().test(
                new Reasoning.LoopDetected("the same substantial line, again", 6)));
    }

    @Test
    @Timeout(60)
    void theLivenessGuardIsReachableWithoutTheClientToo() {
        // ratchet#5 has its own silence ceiling and did not want Model's; a consumer that would
        // rather have this one should be able to take it without taking everything around it.
        // The guard is no longer a wrapper around somebody else's client — this library owns the
        // socket now, so the guard lives in Wire's read loop and what a consumer hands in is its
        // own Watch, through the door below. Wire.to is called here rather than the constructor
        // because the constructor is this package's and a consumer cannot reach it; the cast is
        // this test's, so the read loop can be fed frames instead of a socket.
        Wire guarded = (Wire) Wire.to(SOMEWHERE, Sampling.deterministic(), theirOwnPatience(),
                true, QUIET);

        // An ordinary answer passes through, which is the half the wrapper this replaces proved.
        assertEquals("said something", guarded.read(ended("stop", "said something")).said());

        // AND THE BOUND THAT DECIDES IS THEIRS, WHICH IS THE HALF NOTHING PROVED. This assertion
        // was added after a mutation survived the whole module: a door that takes a Watch and then
        // builds the client with Watch.fromEnv() left every test in ratchet-llm green, because the
        // only tests that hand a Watch to anything hand it to Wire's package-private constructor.
        // That is exactly ratchet#5's request going unchecked in the file that exists for it — the
        // unit was tested and the wiring to it was not. With their millisecond bound in force this
        // silent stream ends; with the shipped twenty minutes it would not, so the @Timeout is the
        // assertion for that mutation: it must go red rather than hang.
        //
        // IT COSTS ONE TICK OF WALL CLOCK, and that is not the Watch's doing. The read loop notices
        // silence when its own fifteen-second poll times out, so no bound below that can fire
        // sooner — the same fifteen seconds WhatAStalledStreamHadAlreadySaidTest records paying.
        IllegalStateException silent = assertThrows(IllegalStateException.class,
                () -> guarded.read(saysNothingEver()));
        assertTrue(silent.getMessage().contains("not producing"), silent.getMessage());
    }

    @Test
    void aConsumerCanRefuseTheTruncationItselfBecauseTheTypeIsPublic() {
        Wire guarded = new Wire(SOMEWHERE, Sampling.deterministic(), theirOwnPatience(), true, QUIET);

        Truncated cut = assertThrows(Truncated.class,
                () -> guarded.read(ended("length", "")));
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
    void theChosenTemperatureReachesTheClientAndNotJustTheRecord() throws IOException {
        // THE SEAM, NOT THE VALUE. An earlier version of this file asserted only that Sampling
        // HELD 1.0, and hardcoding temperature(0.0) back into build() turned no test red — the
        // record was right and nothing proved the client was told. The assembled chain used to be
        // askable for its default request parameters; a one-method Chat has no such question to
        // answer, and Wire.body() alone would not do — that proves the client writes the Sampling
        // it was GIVEN, which was never the mutation that got through. So the question goes to the
        // wire: the whole chain, pointed at a loopback that answers one frame, and what arrives is
        // the request.
        String sent = sentBy(where -> Model.forProducer(QUIET, where, Retry.none(),
                Sampling.asTheModelRequires(1.0)));

        assertTrue(sent.contains("\"temperature\":1.0"),
                "the model a consumer gets must answer at the temperature it asked for: " + sent);
    }

    @Test
    void theChosenCompletionBudgetReachesTheClientToo() throws IOException {
        // Found by the same mutation one field over: temperature was proved at the seam and
        // maxTokens was not, so hardcoding it back turned nothing red. Both halves of Sampling
        // that go on the wire are asserted where they land, not where they are stored.
        String sent = sentBy(where -> Model.forProducer(QUIET, where, Retry.none(),
                Sampling.deterministic().withThinkingTokens(512).withMaxTokens(2_048)));

        assertTrue(sent.contains("\"max_tokens\":2048"), sent);
        // The thinking budget had to come down first, because the invariant refuses a reasoning
        // budget that swallows the pool — it caught this test before the test caught anything.
    }

    @Test
    void theShippedDefaultIsStillZeroAllTheWayDown() throws IOException {
        String sent = sentBy(where -> Model.forProducer(QUIET, where, Retry.none(),
                Sampling.deterministic()));

        assertTrue(sent.contains("\"temperature\":0.0"),
                "most replies here are branched on, and that reason has not changed: " + sent);
    }

    @Test
    void theTwoBudgetsAreSeparateNumbersEvenThoughTheServerPoolsThem() {
        // Measured: thinkingTokens is the lever that decides whether there is an answer at all.
        // 11,700 characters of reasoning and none left for a reply, against 233 and a full answer.
        Sampling roomToAnswer = Sampling.deterministic().withThinkingTokens(500);

        assertEquals(500, roomToAnswer.thinkingTokens());
        assertEquals(16_000, roomToAnswer.maxTokens());
        assertTrue(Wire.body(ask(), SOMEWHERE, roomToAnswer, true)
                        .contains("\"thinking_token_budget\":500"),
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
        assertFalse(Wire.body(ask(), SOMEWHERE, Sampling.deterministic().withThinkingTokens(0), true)
                        .contains("thinking_token_budget"),
                "no budget field at all when there is no thinking to bound");
    }

    // ---------------------------------------------------------------- the fakes

    private static final Now FROZEN = Now.frozenAt(0);

    /** Never opened: {@link Wire#body} is a pure function and {@link Wire#read} is handed frames. */
    private static final Endpoint SOMEWHERE = Endpoint.of("http://127.0.0.1:1/v1", "some-model");

    private static Ask ask() {
        return Ask.of(List.of(Said.user("who speaks first?")));
    }

    /**
     * BOUNDS OF THE CONSUMER'S OWN CHOOSING, WHICH IS THE WHOLE OF ratchet#5'S REQUEST.
     *
     * <p>Neither number is Model's, which is the point; the stall is a millisecond rather than the
     * two minutes a real consumer would pick, because a bound that cannot be observed inside a test
     * is a bound the door could quietly drop. Milliseconds are only sayable at all because
     * {@link Watch} is a value now and not a constant parsed at class load.
     */
    private static Watch theirOwnPatience() {
        return new Watch(Duration.ofMillis(1), Duration.ofMinutes(30));
    }

    /** Counted down by nothing, which is the point. */
    private static final CountDownLatch SILENT = new CountDownLatch(1);

    /**
     * A connection that is open and has stopped producing.
     *
     * <p>It must BLOCK rather than end: a stream that finishes is a completed response, and the
     * guard being reached here is the one for silence. The thread parked on it is {@link Wire}'s
     * own daemon reader, so a stream abandoned mid-stall holds up neither the next test nor the
     * JVM's exit.
     */
    private static Stream<String> saysNothingEver() {
        return Stream.generate(() -> {
            try {
                SILENT.await();
                return "";
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the silent stream was interrupted", stopping);
            }
        });
    }

    /**
     * One generation, in the frame shapes captured from the production endpoint: the opening role
     * delta, what it said, the finish reason, then {@code [DONE]}.
     */
    private static Stream<String> ended(String why, String content) {
        return Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                        + "\"content\":\"\"},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},"
                        + "\"finish_reason\":\"" + why + "\"}]}",
                "",
                "data: [DONE]");
    }

    /**
     * WHAT THE CLIENT ACTUALLY SENDS, TAKEN OFF THE SOCKET RATHER THAN OFF THE OBJECT.
     *
     * <p>A loopback server on an ephemeral port, one request, one frame back. It exists because the
     * two mutations this file was written against — temperature and the completion budget hardcoded
     * back inside {@code build} — both live in the wiring between {@link Model} and {@link Wire},
     * and a test that asserted either end alone stayed green through them. Nothing here waits: the
     * server answers immediately, so the read loop's first poll returns and the whole exchange is a
     * few milliseconds.
     */
    private static String sentBy(Function<Endpoint, Chat> theirs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> sent = new AtomicReference<>("");
        server.createContext("/v1/chat/completions", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(answer);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Chat built = theirs.apply(
                    Endpoint.of("http://127.0.0.1:" + port + "/v1", "some-model"));
            assertEquals("ok", built.answer(ask()).said(), "the loopback stood in for the endpoint");
            return sent.get();
        } finally {
            server.stop(0);
        }
    }

    /**
     * A consumer's own model — built however they like, dropping its first calls.
     *
     * <p>ONE METHOD. This used to be two, {@code chat} and {@code doChat}, because the interface it
     * implemented had five and a fake that overrode the wrong one was silently never called. There
     * is nothing here now that is not the consumer's own behaviour.
     */
    private static final class Flaky implements Chat {
        final AtomicInteger calls = new AtomicInteger();
        private final int drops;

        Flaky(int drops) {
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
