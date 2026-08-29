package tech.mikhailov.ratchet.consumer;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import tech.mikhailov.ratchet.llm.Ask;
import tech.mikhailov.ratchet.llm.Chat;
import tech.mikhailov.ratchet.llm.Endpoint;
import tech.mikhailov.ratchet.llm.Keeping;
import tech.mikhailov.ratchet.llm.Model;
import tech.mikhailov.ratchet.llm.Retry;
import tech.mikhailov.ratchet.llm.Said;
import tech.mikhailov.ratchet.llm.Sampling;
import tech.mikhailov.ratchet.llm.Watch;
import tech.mikhailov.ratchet.llm.Wire;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THIS FILE IS IN ANOTHER PACKAGE, AND THAT IS THE WHOLE POINT OF IT.
 *
 * <p>Seven times this library has made a value injectable for its own tests and left it unreachable
 * from outside — {@code Backoff}/{@code Pause}, {@code Now}, {@code Endpoint}, {@code Watch} and
 * three more — and every one was reported by a consumer rather than found here. {@code Watch}'s own
 * javadoc says so. The mechanical reason is that every other test in this module sits in
 * {@code tech.mikhailov.ratchet.llm} and so has package-private access for free: a seam that stops
 * one step short of the boundary is green in-house and unusable in the field.
 *
 * <p>Three tests in this module claim to check exactly that and cannot. "the predicate is reachable
 * on its own", "the liveness guard is reachable without the client too" and "a consumer can refuse
 * the truncation itself because the type is public" would all pass unchanged if every type they
 * name were package-private, and the third reaches a package-private constructor on its first line.
 *
 * <p>This file has no such access. If {@link Keeping}, any factory on it, or the {@link Wire} and
 * {@link Model} doors below were narrowed, this would fail to COMPILE rather than ship. That is the
 * only form of this proof that is worth anything, and it costs one directory.
 *
 * <p>WHY BOTH DIRECTIONS. A test that only widens the bound passes against a client that ignores
 * the value and never clips; a test that only narrows it passes against one that ignores the value
 * and always clips at something small. The bounds handed in below are numbers no shipped default
 * can produce — 64, and 1,234 — so neither {@code Keeping.shipped()} nor {@code Keeping.fromEnv()}
 * hardcoded back inside the door survives both.
 */
class AConsumerOutsideThisPackageChoosesWhatTheRecordKeepsTest {

    private static final String ANSWER = "z".repeat(5_000);

    @Test
    void aConsumerOutsideThisPackageKeepsTheWholeExchangeThroughTheClientsOwnDoor()
            throws IOException {
        Kept record = new Kept();

        answered(record, endpoint -> Wire.to(endpoint, Sampling.deterministic(), Watch.shipped(),
                false, record, Keeping.everything()));

        assertEquals(ANSWER, record.answers.get(0),
                "everything() means everything: a consumer with no other copy of what the model "
                        + "said gets all of it, which before this seam meant forking the library");
    }

    @Test
    void aConsumerOutsideThisPackageCanBindTheAnswerTighterThanTheRecordShips()
            throws IOException {
        Kept record = new Kept();

        answered(record, endpoint -> Wire.to(endpoint, Sampling.deterministic(), Watch.shipped(),
                false, record, Keeping.shipped().withAnswer(64)));

        assertEquals("z".repeat(64) + "\n... (truncated, total 5000 chars)", record.answers.get(0),
                "cut where the consumer said, and saying how much there was rather than only that "
                        + "there had been more");
    }

    /**
     * THE DOOR MOST CONSUMERS ACTUALLY ENTER BY. The one that reported this bound enters at
     * {@link Model#forProducer}, where before this change the only way to reach the record's bounds
     * was the environment.
     */
    @Test
    void theKeepingAConsumerHandsInIsTheOneTheRowIsWrittenBy() throws IOException {
        Kept record = new Kept();

        answered(record, endpoint -> Model.forProducer(record, endpoint, Retry.none(),
                Sampling.deterministic(), Watch.shipped(), Keeping.shipped().withAnswer(1_234)));

        assertEquals("z".repeat(1_234) + "\n... (truncated, total 5000 chars)",
                record.answers.get(0),
                "1,234 is a bound no default of this library can produce, so a door that quietly "
                        + "read the environment or the shipped value instead would fail here");
    }

    @Test
    void theRenderKeepsLessThanTheBriefAndTheAnswerBecauseItAloneIsPaidAgain() {
        Keeping shipped = Keeping.shipped();

        assertTrue(shipped.turn() < shipped.prompt() && shipped.turn() < shipped.answer(),
                "the render is re-emitted on every later call of the same conversation and the "
                        + "other two are written once, so a shipped bound that treated them alike "
                        + "would be wrong about one of them whichever number it chose");
    }

    @Test
    void aBoundOfZeroIsRefusedBecauseAnAlwaysEmptyColumnIsNotASmallerRecord() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Keeping.shipped().withTurn(0)).getMessage().contains("turn"),
                "and it says which column, because three bounds means three ways to be wrong");
        assertThrows(IllegalArgumentException.class, () -> Keeping.shipped().withAnswer(-1));
        assertThrows(IllegalArgumentException.class, () -> Keeping.shipped().withPrompt(0));
    }

    @Test
    void aDeploymentThatSetsNothingGetsTheShippedBounds() {
        assertEquals(Keeping.shipped(), Keeping.fromEnv(),
                "no launcher in this repository names these three variables, so the environment "
                        + "door must land on the shipped numbers rather than on a parser's own");
    }

    /**
     * A loopback on an ephemeral port answering one frame, which is this module's own pattern for
     * taking behaviour off the socket rather than off the object. The exchange row is written by
     * the client on the way past, so what this asserts is what a corpus would hold.
     */
    private static void answered(Kept record, Function<Endpoint, Chat> theirs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] frame = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + ANSWER
                    + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, frame.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(frame);
            }
        });
        server.start();
        try {
            Chat built = theirs.apply(
                    Endpoint.of("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "m"));
            assertEquals(ANSWER, built.answer(
                    Ask.of(List.of(Said.user("go")))).said(), "the loopback stood in for a server");
        } finally {
            server.stop(0);
        }
    }

    /**
     * A consumer's trace, which is eight empty methods and one that keeps what it is given. The
     * boilerplate is the honest cost: this is what somebody outside actually has to write.
     */
    private static final class Kept implements Trace {

        private final List<String> answers = new ArrayList<>();

        @Override
        public void exchanged(Trace.Exchange exchange) {
            if ("back".equals(exchange.direction())) {
                answers.add(exchange.got());
            }
        }

        @Override public void asked(String agent, String prompt, String reply) { }

        @Override public void applied(String stage, String what) { }

        @Override public void tool(String agent, String tool, String arguments, String result) { }

        @Override public void thought(String finish, String thinking, String content) { }

        @Override public void built(String phase, Trace.Outcome result) { }

        @Override public void settled(String key, String state, String because, boolean before,
                                      boolean after) { }

        @Override public void failed(String key, Throwable cause) { }

        @Override public void progress(String key, String note) { }

        @Override public void priced(String key, String minutes, String itemisation) { }
    }
}
