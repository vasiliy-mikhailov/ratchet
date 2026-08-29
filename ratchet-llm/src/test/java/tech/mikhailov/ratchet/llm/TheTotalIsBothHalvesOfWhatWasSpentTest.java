package tech.mikhailov.ratchet.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TOTAL IS BOTH HALVES ADDED, AND IT MUST AGREE WITH THE NUMBER THE SERVER SENT IN THE SAME
 * FRAME.
 *
 * <p>The endpoint counts the call itself and says so three ways at once. The frame captured from
 * this project's production endpoint, and pasted into two test files here already, is
 *
 * <pre>
 * data: {"choices":[],"usage":{"prompt_tokens":57,"total_tokens":69,"completion_tokens":12}}
 * </pre>
 *
 * <p>{@link Wire#read} takes the two halves and DELIBERATELY IGNORES {@code total_tokens}
 * ({@code Wire.java:256}), so {@link Spend#total()} is this library re-deriving a number the server
 * has already computed and is sitting right next to. Nothing ever compares the two. That is the
 * whole exposure: an addition that is checked against nothing, standing in for an authority that
 * was in the same eight bytes of JSON.
 *
 * <p>WHY IT WENT UNCHECKED FOR A RELEASE: {@code grep -rn '\.total()'} across the PRODUCTION source
 * of ratchet AND ratchet-ui returns NOTHING — not one call site outside test code, in either
 * repository. {@link Asking#run} returns a String, so a
 * per-turn spend never reaches an agent's caller at all, and the record path bypasses the method
 * entirely — {@code Trace.Exchange} carries {@code inTokens} and {@code outTokens} as two separate
 * fields and {@code JsonlTrace} writes them as {@code "in"} and {@code "out"}. The only caller of
 * {@code total()} is a consumer, outside this repository, which is the sixth time a seam in this
 * library stopped one step short of the package boundary. PIT scored the class 0%.
 *
 * <p>AND BOTH WAYS IT CAN BE WRONG LOOK LIKE SOMETHING ELSE. Subtract instead of add and this
 * frame reports 45; in a tool loop, where the whole conversation is re-prefilled every turn and the
 * prompt half dominates, that is a 28% under-count that still reads like a plausible bill (asserted
 * below). Return zero and it is indistinguishable from the failure {@link Sampling}'s javadoc
 * already warns about — a proxy that drops unknown fields swallowing
 * {@code stream_options.include_usage}, so no usage frame is ever sent and every call looks free.
 * That is why the test at the bottom goes through a socket and a server that only sends usage when
 * it was asked for: zero has to mean NOT TOLD, and it must not be reachable any other way.
 *
 * <p>What rests on this: the reason {@link Spend} was made a first-class field is a question a
 * character count cannot answer — what the thinking budget ACTUALLY spent, against what it was set
 * to. {@code Sampling}'s own measurement (no budget: 11,700 characters of thinking and an empty
 * answer; 50 tokens: 233 and a 1,261-character answer) is in characters precisely because the
 * tokens were unreachable then. They are reachable now, and they are only worth reaching if they
 * add up.
 */
class TheTotalIsBothHalvesOfWhatWasSpentTest {

    /** The frame as the production endpoint sends it, {@code total_tokens} and all. */
    private static final String USAGE = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":57,"
            + "\"total_tokens\":69,\"completion_tokens\":12}}";

    @Test
    void theTotalIsWhatTheServerItselfCalledTheTotal() {
        // THE ASSERTION THAT COULD ALWAYS HAVE BEEN MADE AND NEVER WAS. Nothing here is a restated
        // sum: every number is read out of the server's own frame, including the answer, so this
        // test is the reconciliation between our arithmetic and the endpoint's.
        int prompt = Json.number(USAGE, "prompt_tokens", -1);
        int completion = Json.number(USAGE, "completion_tokens", -1);
        int theServersOwnTotal = Json.number(USAGE, "total_tokens", -1);

        assertEquals(theServersOwnTotal, new Spend(prompt, completion).total(),
                "the endpoint counted " + theServersOwnTotal + " for this call and this library "
                        + "reads past that field; a total that subtracts reports 45 and a total "
                        + "that returns 0 reports free, and both are stated as confidently as 69");
    }

    @Test
    void theCountComesOffTheWireAndSurvivesIntoTheReply() {
        // The unit above proves the arithmetic; this proves the arithmetic is wired to the frame.
        // Spend is built inside the read loop and nothing else in this repository ever calls
        // total(), so a Spend assembled by hand in a test would be the only Spend ever totalled.
        Reply reply = client().read(generation("Pierre Bezúkhov is the", 57, 12));

        assertEquals(57, reply.spend().prompt(), "what the conversation cost to re-send");
        assertEquals(12, reply.spend().completion(), "and what the model wrote back");
        assertEquals(69, reply.spend().total(),
                "the two halves the server sent, added, which is what a consumer bills against");
    }

    @Test
    void aSweepAddsUpAndTheHalvesAreNotInterchangeable() {
        // WHY THE METHOD EXISTS AT ALL, per Spend's javadoc: a caller adding up a sweep should not
        // have to ask. Three turns of one tool loop, with the prompt growing because a request here
        // is the whole accumulated conversation re-prefilled — so the prompt half dominates and a
        // sign error hides inside a number that still looks like a bill.
        int[][] turns = {{1_200, 40}, {1_310, 512}, {1_450, 90}};

        int spent = 0;
        for (int[] turn : turns) {
            spent += client().read(generation("...", turn[0], turn[1])).spend().total();
        }

        assertEquals(4_602, spent, "3,960 sent and 642 written back");
        assertNotEquals(3_318, spent,
                "subtracting gives 3,318 here: a 28% under-count of a corpus's spend, in a number "
                        + "nobody would look at twice");
        assertNotEquals(3_960, spent, "and dropping the completion half loses the part that varies");
    }

    @Test
    void aTurnWhoseUsageNeverArrivedTotalsZeroAndThatIsNotTheSameAsFree() {
        // The other half of the same requirement. Zero is a legitimate answer — Spend.NONE exists so
        // a caller summing a sweep never has to test for null — but it must mean WE WERE NOT TOLD,
        // and the only way to be not told is for the server to send no usage frame.
        Reply quiet = client().read(Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]"));

        // THE GENERATION ITSELF MUST HAVE ARRIVED, or the zero below is the zero of a read that did
        // nothing rather than the zero of a server that said nothing, and the two are the whole
        // distinction this test exists to draw.
        assertEquals("ok", quiet.said(),
                "the answer came through; only the usage frame is absent, which is the case a "
                        + "proxy that drops stream_options produces on every call in a run");
        assertEquals(Spend.NONE, quiet.spend(), "no usage frame means no counts, not null");
        assertEquals(0, quiet.spend().total(), "and nothing to add up");
        assertEquals(0, Spend.NONE.total(), "the identity a sweep starts from");
    }

    @Test
    @Timeout(30)
    void theCountSurvivesARealRequestBecauseTheRequestAsksForUsage() throws IOException {
        // END TO END, THROUGH A SOCKET, because the failure this guards against is not arithmetic:
        // it is a run where include_usage never reached the server and every total is honestly zero.
        // The loopback endpoint below behaves the way the real one does — it sends the usage frame
        // ONLY when the request asked for it — so a total of 69 here proves the field went out, the
        // frame came back, and the sum reached the caller of Chat.answer.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> asked = new AtomicReference<>("");
        server.createContext("/v1/chat/completions", exchange -> {
            String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            asked.set(sent);
            String frames = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\n"
                    + (sent.contains("\"include_usage\":true") ? USAGE + "\n\n" : "")
                    + "data: [DONE]\n\n";
            byte[] answer = frames.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(answer);
            }
        });
        server.start();
        try {
            Chat model = Wire.to(
                    Endpoint.of("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "a-model"),
                    Sampling.deterministic(), Watch.shipped(), true, Trace.quiet());

            Reply reply = model.answer(Ask.of(List.of(Said.user("who speaks first?"))));

            assertTrue(asked.get().contains("\"include_usage\":true"),
                    "the one request field with no predecessor, without which there is no usage "
                            + "frame and no total to report: " + asked.get());
            assertEquals(69, reply.spend().total(),
                    "the count crossed a real connection and arrived as one number the caller can "
                            + "add to the next one");
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------- the fakes

    /** A client with no socket under it, so the frames go straight into {@link Wire#read}. */
    private static Wire client() {
        return new Wire(Endpoint.of("http://localhost:1", "a-model"), Sampling.deterministic(),
                Watch.shipped(), true, Trace.quiet());
    }

    /**
     * One generation in the shapes captured from the production endpoint: the content, the finish
     * reason, then the usage chunk whose {@code choices} array is empty, then {@code [DONE]}.
     */
    private static Stream<String> generation(String content, int prompt, int completion) {
        return Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":" + prompt + ","
                        + "\"total_tokens\":" + (prompt + completion) + ","
                        + "\"completion_tokens\":" + completion + "}}",
                "",
                "data: [DONE]");
    }
}
