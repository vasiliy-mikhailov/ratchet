package tech.mikhailov.ratchet.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OWNING THE SOCKET MEANS OWNING THE HEADER, THE STATUS, THE THREAD AND THE CLOSE, AND NOTHING IN
 * THIS REPOSITORY HAD AN OPINION ABOUT ANY OF THEM.
 *
 * <p>The argument for dropping the client library is written at the top of {@link Wire} and it is a
 * good one: thirteen jars and eight megabytes for one POST and one SSE reader, three classes here
 * existing only to work around it. What the arithmetic left out is that the jar was also doing
 * things nobody had to think about, and they all arrived here at once, in a rewrite that is days
 * old.
 *
 * <p>A mutation run over this module measured how much of that is checked. 205 mutants are alive in
 * it and 70 of them are in {@link Wire}: 54 survive a full test run and 16 are never executed at all
 * — the biggest hole in the module, in its newest code. The tests around it are thorough about the
 * things that were PAINFUL before (the reasoning field, the stall, the truncation) and silent about
 * everything that used to be somebody else's problem:
 *
 * <pre>
 * the bearer token          removing the header entirely turns nothing red
 * a base URL's trailing /   no test has ever passed one
 * a non-200 response        deleting the status check turns nothing red
 * the reader thread         nothing asserts it is a daemon; a non-daemon one wedges the JVM
 * the body's close()        deleting it turns nothing red
 * an interrupt              both restore-the-flag lines are never executed
 * the ceiling               never executed by anything: GaveUp is only ever built by hand
 * </pre>
 *
 * <p>Every test below is one of those lines. None of them needs a model and only the ones about the
 * request itself need a socket, which is a loopback {@code com.sun.net.httpserver} on an ephemeral
 * port — the pattern {@code AConsumerBringsItsOwnModelTest} established for exactly this: a unit
 * that passes in isolation and is wired to nothing is the failure this library exists to argue
 * against.
 */
class TheClientOwnsTheSocketAndEverythingOnItTest {

    // ------------------------------------------------------------------ what goes out

    @Test
    @Timeout(20)
    void theSilenceIsReportedInAUnitThatSurvivesBeingSmall() {
        // THIS TEST PINNED THE BUG IT WAS WRITTEN TO CATCH. It asserted the silence was rendered
        // in MINUTES — `quiet / 60_000` — which is right for the shipped twenty-minute stall and
        // wrong for every value ratchet#7 made possible: a consumer with a two-minute patience was
        // told "no token for 0 minutes", which reads as a broken guard rather than a fact about
        // the connection. A sub-minute bound now reports itself in seconds.
        Wire wire = new Wire(Endpoint.of("http://test/v1", "m"), Sampling.deterministic(),
                new Watch(Duration.ofMillis(200), Duration.ofSeconds(30)), true, null);

        IllegalStateException stalled = assertThrows(IllegalStateException.class,
                () -> wire.read(Stream.generate(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException stopping) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(stopping);
                    }
                    return "";
                })));

        assertTrue(!stalled.getMessage().contains("0 minutes")
                        && !stalled.getMessage().contains("0m"),
                "a bound that reports itself as zero is not a report: " + stalled.getMessage());
        assertTrue(stalled.getMessage().contains("not producing"), stalled.getMessage());
    }

    @Test
    @Timeout(60)
    void aStallAfterTheModelFINISHEDSaysWhyTheMODELStopped() {
        // TWO THINGS ENDED AND THEY ENDED FOR DIFFERENT REASONS. The generation finished — the
        // server said `stop` and the tokens are all here — and then the connection sat open and
        // silent, which is a proxy holding a socket rather than a model in trouble. The row records
        // the GENERATION, so it must keep the server's own word; the guard's sentence is the
        // exception's business and reaches the caller by that road.
        //
        // Reversing this reads as harmless and is not: a corpus of thought rows all saying "no
        // token for 0 minutes" hides every finish reason behind whichever guard fired last, and
        // the finish reason is what Truncated, Insisting and Ending all branch on.
        Notes notes = new Notes();
        Wire impatient = client(SOMEWHERE,
                new Watch(Duration.ofMillis(300), Duration.ofMinutes(5)), notes);

        assertThrows(IllegalStateException.class,
                () -> impatient.read(thinksThenStalls("it had already said everything ", "stop")));

        assertEquals(1, notes.thoughts.size(), notes.thoughts.toString());
        assertTrue(notes.thoughts.get(0).startsWith("stop :: "),
                "the model finished; only the socket stalled: " + notes.thoughts.get(0));
    }

    @Test
    @Timeout(60)
    void theBodyIsCLOSEDOnTheWayOutOfEitherKindOfEnding() {
        // A STREAM LEFT OPEN IS A GENERATION THE SERVER KEEPS PAYING FOR. Closing the body is what
        // makes the model stop — it is the stated reason the runaway detector throws out of this
        // loop rather than swallowing the detection — and deleting the close leaves every test in
        // this module green, because a Reply that is correct is a Reply nobody asks about the
        // socket underneath.
        AtomicBoolean afterAnAnswer = new AtomicBoolean();
        reading(Trace.quiet()).read(Stream.of(
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"an answer\"},"
                                + "\"finish_reason\":\"stop\"}]}",
                        "",
                        "data: [DONE]")
                .onClose(() -> afterAnAnswer.set(true)));

        assertTrue(afterAnAnswer.get(), "an ordinary answer still has a connection to give back");

        AtomicBoolean afterATruncation = new AtomicBoolean();
        assertThrows(Truncated.class, () -> reading(Trace.quiet()).read(Stream.of(
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"and the whole "
                                + "budget went here\"},\"finish_reason\":null}]}",
                        "",
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},"
                                + "\"finish_reason\":\"length\"}]}",
                        "",
                        "data: [DONE]")
                .onClose(() -> afterATruncation.set(true))));

        assertTrue(afterATruncation.get(),
                "and the failing path is the one that matters more: a truncation is a call that "
                        + "will be asked again, and it must not leave the first one running");
    }

    @Test
    @Timeout(60)
    void theReaderThreadIsADaemonOrAStalledSweepsJVMNEVEREXITS() {
        // THE THREAD OUTLIVES THE CALL, ALWAYS. A stall does not stop the reader — it is blocked
        // inside the JDK on a socket that has stopped producing, which is the entire reason the
        // reading happens on its own thread — so every stalled call in a sweep leaves one of these
        // parked for good. Non-daemon, that is a JVM that never exits: a nightly sweep that
        // finished its work at 04:00 and a build that hangs after the last test passes.
        //
        // Two existing test files describe this thread as a daemon in prose. Nothing asserts it,
        // and the setDaemon call survives being deleted.
        Wire impatient = client(SOMEWHERE,
                new Watch(Duration.ofMillis(300), Duration.ofMinutes(5)), Trace.quiet());

        assertThrows(IllegalStateException.class, () -> impatient.read(saysNothingEver()));

        List<Thread> parked = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "ratchet-wire-reader".equals(t.getName())).toList();
        assertFalse(parked.isEmpty(),
                "the reader is still on the stream that never ended, which is the point");
        parked.forEach(reader -> assertTrue(reader.isDaemon(),
                "a reader that is not a daemon is a JVM that never exits after a stalled sweep"));
    }

    @Test
    @Timeout(30)
    void anInterruptedSTREAMLeavesTheInterruptWhereItFoundIt() throws InterruptedException {
        // CANCELLING A SWEEP MEANS INTERRUPTING THE THREADS IN IT, and a thread that catches an
        // InterruptedException and does not restore the flag has silently declined to be cancelled:
        // the next blocking call it makes does not see it, and a lane that was told to stop asks
        // the model again. The line that restores it is never executed by any test.
        CountDownLatch reading = new CountDownLatch(1);
        AtomicReference<RuntimeException> stopped = new AtomicReference<>();
        AtomicBoolean flagSurvived = new AtomicBoolean();

        Thread lane = new Thread(() -> {
            try {
                client(SOMEWHERE, Watch.shipped(), Trace.quiet())
                        .read(silenceThatSaysItHasBegun(reading));
            } catch (RuntimeException cancelled) {
                stopped.set(cancelled);
                flagSurvived.set(Thread.currentThread().isInterrupted());
            }
        }, "a-lane-being-cancelled");
        lane.setDaemon(true);
        lane.start();

        assertTrue(reading.await(20, TimeUnit.SECONDS), "the lane got as far as waiting for tokens");
        lane.interrupt();
        lane.join(20_000);

        assertNotNull(stopped.get(), "an interrupted wait has to end the call, not resume it");
        assertTrue(stopped.get().getMessage().contains("interrupted while streaming"),
                stopped.get().getMessage());
        assertTrue(flagSurvived.get(),
                "and the interrupt is left set for whatever this thread does next: swallowing it is "
                        + "how a cancelled pool keeps talking to the endpoint");
    }

    @Test
    @Timeout(30)
    void anInterruptedREQUESTLeavesTheInterruptWhereItFoundItToo() throws Exception {
        // THE SAME REQUIREMENT AT THE OTHER END OF THE CALL, and the likelier one of the two. A
        // request spends its first minutes waiting for the server to prefill a conversation that
        // grows with every tool call, so a cancellation lands here far more often than it lands
        // mid-stream. This line is never executed either.
        try (Loopback held = Loopback.thatNeverAnswers()) {
            AtomicReference<RuntimeException> stopped = new AtomicReference<>();
            AtomicBoolean flagSurvived = new AtomicBoolean();

            Thread lane = new Thread(() -> {
                try {
                    client(held.at(""), Watch.shipped(), Trace.quiet()).answer(ask());
                } catch (RuntimeException cancelled) {
                    stopped.set(cancelled);
                    flagSurvived.set(Thread.currentThread().isInterrupted());
                }
            }, "a-lane-waiting-on-the-first-token");
            lane.setDaemon(true);
            lane.start();

            assertTrue(held.reached.await(20, TimeUnit.SECONDS), "the request reached the endpoint");
            lane.interrupt();
            lane.join(20_000);

            assertNotNull(stopped.get(), "the call has to end rather than keep waiting");
            assertTrue(stopped.get().getMessage().contains("interrupted before the model answered"),
                    stopped.get().getMessage());
            assertTrue(flagSurvived.get(),
                    "and the flag survives, for the same reason it must survive a stream");
        }
    }

    // ---------------------------------------------------------------- the fakes

    /** Never opened: {@link Wire#read} is handed frames and {@link Wire#body} is a pure function. */
    private static final Endpoint SOMEWHERE = Endpoint.of("http://127.0.0.1:1/v1", "a-model");

    private static Ask ask() {
        return Ask.of(List.of(Said.user("who speaks first?")));
    }

    private static Wire client(Endpoint where, Watch watch, Trace trace) {
        return new Wire(where, Sampling.deterministic(), watch, true, trace);
    }

    /** A client with no socket under it, on the shipped bounds, for feeding frames directly. */
    private static Wire reading(Trace trace) {
        return client(SOMEWHERE, Watch.shipped(), trace);
    }

    /**
     * TWO CALLS IN ONE TURN, INTERLEAVED, WITH THE ARGUMENTS IN FRAGMENTS.
     *
     * <p>The shape captured from the production endpoint: the first delta of a call carries its id
     * and its name and no arguments, and every delta after it carries only an index and the next
     * piece of the arguments JSON. The two calls alternate, and the index that separates them sits
     * inside {@code tool_calls} beneath a choice-level {@code index} that is always zero — which is
     * why a flat read of the chunk files the second call's arguments against the first.
     *
     * <p>The arguments are a grep pattern and a glob, both of which carry a brace inside a JSON
     * string, because that is what a model asking to search a repository actually sends.
     */
    private static Stream<String> twoCallsInOneTurn() {
        return Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call_a\","
                        + "\"type\":\"function\",\"index\":0,\"function\":{\"name\":\"grep\","
                        + "\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call_b\","
                        + "\"type\":\"function\",\"index\":1,\"function\":{\"name\":\"glob\","
                        + "\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"pattern\\\":\\\"^func \"}}]},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,"
                        + "\"function\":{\"arguments\":\"{\\\"glob\\\":\\\"**/*.{js,ts}\\\"}\"}}]},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{$\\\"}\"}}]},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},"
                        + "\"finish_reason\":\"tool_calls\"}]}",
                "",
                "data: [DONE]");
    }

    /**
     * A generation that finishes and a connection that then goes, before the usage chunk.
     *
     * <p>The failure {@code BodyHandlers.ofLines()} raises is an {@link UncheckedIOException}, and
     * it reaches the read loop the same way every line does — through the queue, from the reader
     * thread — which is the reason the loop has to be able to tell a failure from a line at all.
     */
    private static Stream<String> breaksAfterSaying(String reasoning, String content) {
        return Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"" + reasoning + "\"},"
                        + "\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "and then the socket goes")
                .map(line -> {
                    if (line.startsWith("and then")) {
                        throw new UncheckedIOException("connection reset by peer",
                                new IOException("connection reset by peer"));
                    }
                    return line;
                });
    }

    /**
     * A STREAM THAT IS WORKING AND WILL NOT STOP: one frame a second, then silence for ever.
     *
     * <p>A second is longer than the tick and far shorter than the stall, which is the only window
     * in which the ceiling is reachable at all. The tail never produces, so a ceiling that fails to
     * fire ends as a stall rather than as a hung build.
     *
     * <p>The frames carry no newline on purpose: the runaway detector counts REPEATED LINES, and a
     * fixture that repeated one would be caught by it long before the ceiling.
     */
    private static Stream<String> sayingSomethingEverySecond(int frames) {
        return Stream.concat(Stream.generate(() -> {
            park(Duration.ofSeconds(1));
            return "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"still going. \"},"
                    + "\"finish_reason\":null}]}";
        }).limit(frames), Stream.generate(NOTHING));
    }

    /**
     * Thinks once and never speaks again. The connection stays open, which is the whole case.
     *
     * <p>{@code finish} is the server's own word for why the GENERATION ended, sent or not sent
     * before the silence: {@code ""} for a stream cut off mid-thought, and a real reason for one
     * that had finished and was then left hanging by whatever is between here and the model.
     */
    private static Stream<String> thinksThenStalls(String thought, String finish) {
        List<String> frames = new ArrayList<>();
        frames.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"" + thought + "\"},"
                + "\"finish_reason\":null}]}");
        if (!finish.isEmpty()) {
            frames.add("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                    + finish + "\"}]}");
        }
        return Stream.concat(frames.stream(), Stream.generate(NOTHING));
    }

    private static Stream<String> saysNothingEver() {
        return Stream.generate(NOTHING);
    }

    /** Silent, but it says when the reading has started, so the interrupt lands on a real wait. */
    private static Stream<String> silenceThatSaysItHasBegun(CountDownLatch begun) {
        return Stream.generate(() -> {
            begun.countDown();
            return NOTHING.get();
        });
    }

    /** Counted down by nothing, which is the point. */
    private static final CountDownLatch SILENT = new CountDownLatch(1);

    /**
     * The line that never arrives. The thread waiting on it is {@link Wire}'s own daemon reader, so
     * a lane abandoned mid-stall holds up neither the next test nor the JVM's exit.
     */
    private static final Supplier<String> NOTHING = () -> {
        try {
            SILENT.await();
            return "";
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the silent stream was interrupted", stopping);
        }
    };

    private static void park(Duration how) {
        try {
            Thread.sleep(how.toMillis());
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the slow stream was interrupted", stopping);
        }
    }

    /**
     * THE ENDPOINT, ON LOOPBACK, WHICH IS THE ONLY PLACE THE REQUEST ITSELF IS OBSERVABLE.
     *
     * <p>The header, the path and the status are not values on any object this package exposes —
     * they exist for the length of one exchange, inside a private method, and a test that asserted
     * what {@link Endpoint} HOLDS would have stayed green through every mutation in the first half
     * of this file. So the assertions are made where they land: an ephemeral port on 127.0.0.1, one
     * request, one canned answer, a few milliseconds.
     */
    private static final class Loopback implements AutoCloseable {

        private static final String ONE_ANSWER =
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":57,"
                        + "\"completion_tokens\":12}}\n\n"
                        + "data: [DONE]\n\n";

        private final HttpServer server;
        private final CountDownLatch released = new CountDownLatch(1);

        final CountDownLatch reached = new CountDownLatch(1);
        final AtomicReference<String> authorization = new AtomicReference<>();
        final AtomicReference<String> path = new AtomicReference<>("");

        private Loopback(int status, String answer, boolean holds) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                path.set(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                // Drained before anything else: a request whose body is never read is a client
                // still writing when this handler tries to answer it.
                exchange.getRequestBody().readAllBytes();
                reached.countDown();
                if (holds) {
                    try {
                        released.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException stopping) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                try {
                    exchange.sendResponseHeaders(status, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } catch (IOException goneAway) {
                    // The client stopped listening, which is exactly what one test provokes.
                }
            });
            server.start();
        }

        static Loopback answering() throws IOException {
            return new Loopback(200, ONE_ANSWER, false);
        }

        static Loopback refusing(int status, String why) throws IOException {
            return new Loopback(status, why, false);
        }

        /** Takes the request and never answers it: a prefill that outlasts the caller's patience. */
        static Loopback thatNeverAnswers() throws IOException {
            return new Loopback(200, ONE_ANSWER, true);
        }

        Endpoint at(String key) {
            return new Endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "a-model", key);
        }

        @Override
        public void close() {
            released.countDown();
            server.stop(0);
        }
    }

    /**
     * A trace that keeps what it is handed.
     *
     * <p>Both lists, because half of this file is about rows that are written on the way OUT of a
     * failure — the exchange row for a call that never answered, and the thought row a guard writes
     * about what the abandoned stream had already produced.
     */
    private static final class Notes implements Trace {

        final List<Trace.Exchange> exchanges = new ArrayList<>();
        final List<String> thoughts = new ArrayList<>();

        @Override
        public synchronized void exchanged(Trace.Exchange exchange) {
            exchanges.add(exchange);
        }

        @Override
        public synchronized void thought(String finishReason, String thinking, String content) {
            thoughts.add(finishReason + " :: " + thinking + " :: " + content);
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
        public void built(String phase, Trace.Outcome result) {
        }

        @Override
        public void settled(String key, String state, String because, boolean beforeOk,
                            boolean afterOk) {
        }

        @Override
        public void failed(String key, Throwable cause) {
        }

        @Override
        public void progress(String key, String note) {
        }

        @Override
        public void priced(String key, String minutes, String itemisation) {
        }
    }
}
