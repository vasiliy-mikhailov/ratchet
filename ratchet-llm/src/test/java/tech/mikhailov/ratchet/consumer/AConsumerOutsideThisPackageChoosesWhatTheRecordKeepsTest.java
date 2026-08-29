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
                false, record, Keeping.shipped().butFor(Keeping.Column.ANSWER, 64)));

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
                Sampling.deterministic(), Watch.shipped(), Keeping.shipped().butFor(Keeping.Column.ANSWER, 1_234)));

        assertEquals("z".repeat(1_234) + "\n... (truncated, total 5000 chars)",
                record.answers.get(0),
                "1,234 is a bound no default of this library can produce, so a door that quietly "
                        + "read the environment or the shipped value instead would fail here");
    }

    @Test
    void theRenderKeepsLessThanTheBriefAndTheAnswerBecauseItAloneIsPaidAgain() {
        Keeping shipped = Keeping.shipped();
        String any = "some text";

        assertTrue(shipped.room(Keeping.Column.TOOL_RESULT, any)
                        < shipped.room(Keeping.Column.PROMPT, any)
                && shipped.room(Keeping.Column.USER, any)
                        < shipped.room(Keeping.Column.ANSWER, any),
                "the render is re-emitted on every later call of the same conversation and the "
                        + "other two are written once, so a shipped bound that treated them alike "
                        + "would be wrong about one of them whichever number it chose");
    }

    @Test
    void aBoundOfZeroIsRefusedBecauseAnAlwaysEmptyColumnIsNotASmallerRecord() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Keeping.of(1, 0, 1)).getMessage().contains("turn"),
                "and it says which column, because three numbers means three ways to be wrong");
        assertThrows(IllegalArgumentException.class, () -> Keeping.of(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Keeping.of(1, 1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> Keeping.shipped().butFor(Keeping.Column.ANSWER, 0));
    }

    @Test
    void aDeploymentThatSetsNothingGetsTheShippedBounds() {
        String any = "some text";
        for (Keeping.Column column : Keeping.Column.values()) {
            assertEquals(Keeping.shipped().room(column, any), Keeping.fromEnv().room(column, any),
                    "no launcher in this repository names these three variables, so the "
                            + "environment door must land on the shipped policy rather than on a "
                            + "parser's own fallback: " + column);
        }
    }


    /**
     * THE DIFFERENCE BETWEEN A POLICY AND A SETTINGS BAG, AND THE REASON THIS IS A FUNCTION.
     *
     * <p>The first version of this seam was three numbers with {@code with} methods. That still had
     * ratchet choosing and a consumer arguing, and the number a consumer had actually reported was
     * still the number ratchet shipped. No fixed number is right for a column whose payloads span
     * three orders of magnitude — one consumer's {@code edit_file} returns at most 214 characters
     * and one {@code grep} returned 3.69 MB — and only the caller knows which of their tools is
     * which.
     *
     * <p>So the bound sees the text. The rule below is a consumer's own and could not be expressed
     * as a setting at any value: keep a compilation failure whole, because that is the thing they
     * open the record for, and hold everything else to a glance.
     */
    @Test
    void aConsumerDecidesPerMessageAndNotOnlyPerColumn() throws IOException {
        String failure = "error: cannot find symbol setStatus(int) on SampleResponse";
        String noise = "the build is green and there is nothing here worth keeping";
        Keeping theirs = (column, text) -> text.contains("cannot find symbol") ? text.length() : 16;
        Kept whenItFailed = new Kept();
        Kept whenItDidNot = new Kept();

        answered(whenItFailed, failure, endpoint -> Wire.to(endpoint, Sampling.deterministic(),
                Watch.shipped(), false, whenItFailed, theirs));
        answered(whenItDidNot, noise, endpoint -> Wire.to(endpoint, Sampling.deterministic(),
                Watch.shipped(), false, whenItDidNot, theirs));

        assertEquals(failure, whenItFailed.answers.get(0),
                "their rule kept it whole, and no number in this library decided that");
        assertEquals(noise.substring(0, 16) + "\n... (truncated, total " + noise.length()
                        + " chars)", whenItDidNot.answers.get(0),
                "and the same policy held the other one to a glance, in the same run");
    }

    /**
     * A loopback on an ephemeral port answering one frame, which is this module's own pattern for
     * taking behaviour off the socket rather than off the object. The exchange row is written by
     * the client on the way past, so what this asserts is what a corpus would hold.
     */
    private static void answered(Kept record, Function<Endpoint, Chat> theirs) throws IOException {
        answered(record, ANSWER, theirs);
    }

    private static void answered(Kept record, String says, Function<Endpoint, Chat> theirs)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] frame = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + says
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
            assertEquals(says, built.answer(
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
