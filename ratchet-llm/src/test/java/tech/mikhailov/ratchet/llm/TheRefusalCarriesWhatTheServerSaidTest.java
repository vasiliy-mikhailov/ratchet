package tech.mikhailov.ratchet.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A REFUSAL CARRIES THE STATUS IT READ OFF THE RESPONSE AND THE WHOLE OF WHAT THE SERVER SAID, AND
 * ITS MESSAGE IS ONE BOUNDED LINE.
 *
 * <p>Written against a mutation run: twelve mutants on {@link Refused}, nine of them SURVIVED, and
 * six of those nine are on the two lines of the constructor that ask whether there is a body at all.
 * {@link Refused#body()} did not even manage to survive — PIT reported its one mutant as
 * NO_COVERAGE, because until this file nothing in either module had ever called it.
 *
 * <p>THE STATUS IS THE WHOLE OF THE RETRY DECISION. {@link Retrying#transportFailures()} is one
 * comparison on {@link Refused#status()} — 408, 429 and 5xx are asked again, every other 4xx is
 * not — so a status that does not survive the trip from the response onto the exception is a run
 * that re-prefills the conversation nine more times for a bad key, or gives up a whole stage on a
 * rate limit that would have cleared in a second. That comparison is asserted already, in
 * AFlakyEndpointIsAskedAgainTest and three files beside it, and this file does not assert it again.
 * Every one of those assertions is made on a {@code Refused} the test built a line earlier. What
 * nothing had checked is the half in front of it: in the suite that mutation run measured, exactly
 * one test stood a server up at all — AConsumerBringsItsOwnModelTest — and it answers 200, so
 * nothing had ever pointed {@link Wire} at an endpoint that refused. {@code Wire}'s own guard is
 * one line, {@code statusCode() != 200}, and it shows in the same run as SURVIVED: with it removed
 * a 400 is handed to the SSE reader, which finds no frames in it, and the run sees an empty answer
 * where a refusal was. The first three tests below go over a loopback socket for that reason — a
 * class that is right about a number nothing hands it is worth nothing — and all three go red on
 * that mutant.
 *
 * <p>THE MESSAGE IS SHORTENED BECAUSE IT IS WRITTEN DOWN, ONCE PER ATTEMPT. {@code Listening.failed}
 * puts {@code getMessage()} in the {@code error} column of an exchange row, {@code Retrying} repeats
 * it in a progress note on every attempt, and {@code JsonlTrace} writes each of those as ONE line of
 * {@code trace.jsonl}. A 502 is answered with the proxy's HTML error page and a 502 is retriable, so
 * an unshortened body is that page escaped onto a record line ten times over. The guard is a
 * whitespace flatten and 300 characters; three surviving mutants removed the cut, moved it and
 * emptied the result, and nothing anywhere went red.
 */
class TheRefusalCarriesWhatTheServerSaidTest {

    @Test
    void theStatusItRefusedWithIsTheOneItReadOffTheResponse() throws IOException {
        // NOT A NUMBER THE TEST HANDED IT. Every other assertion about status() in this module is
        // made on a Refused the test constructed itself, which proves the field and not the read.
        Refused rateLimited = refusalFrom(429, "{\"error\":{\"type\":\"rate_limit_exceeded\"}}");

        assertEquals(429, rateLimited.status(),
                "the status is taken off the response, and it is the only thing the retry judges on");
        assertTrue(Retrying.transportFailures().test(rateLimited),
                "a rate limit read off a real response must still be worth asking again");
    }

    @Test
    void aRequestTheServerCallsWrongIsNotSentAgainUnchanged() throws IOException {
        // The other side of the same line, driven the same way. Wire's own guard is a single
        // `statusCode() != 200`; with it gone a 400 is parsed as a stream, the run sees an empty
        // answer rather than a refusal, and the classification never happens at all.
        Refused wrongRequest = refusalFrom(400,
                "{\"error\":{\"message\":\"unknown field: reasoning_effort\"}}");

        assertEquals(400, wrongRequest.status(),
                "read off the response, like the 429 above: a status that does not make the trip "
                        + "is a bad request sent ten times, since anything unrecognised is retried");
        assertFalse(Retrying.transportFailures().test(wrongRequest),
                "ten identical requests meet the same objection; only a changed request can help");
        assertTrue(wrongRequest.getMessage().contains("unknown field: reasoning_effort"),
                "and the field it objected to is the whole reason a reader opens the record: "
                        + wrongRequest.getMessage());
    }

    @Test
    void theWholeOfWhatTheServerSaidSurvivesTheTrip() throws IOException {
        // WHY THE BODY IS KEPT AT ALL, from Refused's own javadoc: a 400 from a proxy and a 400
        // from the model server say completely different things and only the body distinguishes
        // them. This one is not from the model server, and only these bytes say so.
        String saidByTheProxy = "<html>\n<head><title>502 Bad Gateway</title></head>\n<body>\n"
                + "<center><h1>502 Bad Gateway</h1></center>\n<hr><center>nginx/1.24.0</center>\n"
                + "</body>\n</html>";

        Refused gateway = refusalFrom(502, saidByTheProxy);

        assertEquals(saidByTheProxy, gateway.body(),
                "byte for byte, newlines and all: the hop that refused is named in there and a "
                        + "consumer deciding whether to change its request has to be able to read it");
        assertFalse(gateway.getMessage().contains("\n"),
                "the message, though, is a record line: " + gateway.getMessage());
        assertTrue(gateway.getMessage().contains("<html> <head><title>502 Bad Gateway</title>"),
                "flattened rather than dropped — the page came off the socket with its newlines: "
                        + gateway.getMessage());
    }

    @Test
    void aRefusalWithNothingToSayDoesNotGrowATrailingDash() {
        // Both halves of `body == null || body.isBlank()`, each of which had two live mutants.
        // Forcing the null check the other way is not a cosmetic change: the constructor of the
        // exception that is reporting the failure throws its own NullPointerException, and the
        // status Wire just read is lost behind a stack trace about a different problem entirely.
        assertEquals("the endpoint refused the request: HTTP 500", new Refused(500, null).getMessage(),
                "a null body is nothing said, not a second failure while reporting the first");
        assertEquals("the endpoint refused the request: HTTP 500", new Refused(500, "").getMessage(),
                "and the empty body is the half of that condition the library itself can produce: "
                        + "Wire builds it with joining(\"\\n\") over the response lines, and a "
                        + "refusal answered with no body at all joins to exactly this");
        assertEquals("the endpoint refused the request: HTTP 503",
                new Refused(503, "   \n   ").getMessage(),
                "whitespace is nothing said too; a line ending in a dash with nothing after it "
                        + "reads as a record that was cut off");
    }

    @Test
    void anEmptyBodyIsAShapeTheWireItselfProduces() throws IOException {
        // THE LINE ABOVE ASSERTS THE SHAPE; THIS ONE ASSERTS THAT THE SHAPE ARRIVES. Half of the
        // `body == null || body.isBlank()` condition looks like defence against a caller nobody
        // has — Wire is the only production caller and it joins the response lines, which cannot
        // return null. The other half is not defence at all: a load balancer that refuses with no
        // body at all is joined to "", and that reaches this constructor on an ordinary bad day.
        // Without the isBlank guard the record line for it ends in " — " with nothing after it.
        Refused emptyHanded = refusalFrom(503, "");

        assertEquals("", emptyHanded.body(),
                "no body on the response is nothing said, and it is what joining the lines gives");
        assertEquals("the endpoint refused the request: HTTP 503", emptyHanded.getMessage(),
                "so the record line is the status alone, off a real socket rather than a literal");
    }

    @Test
    void whatTheServerSaidIsAppendedWholeWhenItIsShortEnough() {
        assertEquals("the endpoint refused the request: HTTP 400 — unknown field: reasoning_effort",
                new Refused(400, "unknown field: reasoning_effort").getMessage(),
                "the status alone does not tell a reader which hop objected or to what; this is "
                        + "the line that ends up in the error column and it has to be readable");
    }

    @Test
    void aPageOfHtmlIsFlattenedOntoOneLineAndCutAtTheBound() {
        // THE COST BEING AVOIDED, IN CHARACTERS. This body is 9,000 characters of proxy error page.
        // A 502 is retriable, so it arrives ten times in one stage, and every arrival is a progress
        // note plus an exchange row — twenty record lines carrying the same page.
        String pageOfHtml = "<html>\n  <body>\n    <h1>502 Bad Gateway</h1>\n".repeat(200);

        String message = new Refused(502, pageOfHtml).getMessage();

        assertFalse(message.contains("\n"), "one line, or the record stops being one row per event");
        assertTrue(message.endsWith("(truncated, total 7799 chars)"),
                "and it says HOW MUCH it cut. This wrote a bare ellipsis, the one marker in this "
                        + "library that told a reader something was missing and refused to say how "
                        + "much — body() keeps the whole thing, so a magnitude is what makes that "
                        + "recoverable rather than a guess: " + message);
        assertTrue(message.startsWith(
                        "the endpoint refused the request: HTTP 502 — <html> <body> <h1>502 Bad"),
                "flattened first and then cut, so the 300 characters kept are 300 of the page "
                        + "rather than 300 of its indentation: " + message);
        assertEquals(45 + 300 + " ... (truncated, total 7799 chars)".length(), message.length(),
                "the preamble, 300 characters of the page, and a notice carrying the total — and "
                        + "only the total's own digits move with the size of the page");
    }

    @Test
    void aBodyExactlyAtTheBoundIsSaidWhole() {
        // The boundary, which is a live mutant on its own: `<= 300` weakened to `< 300` cuts a body
        // that fits and marks it with an ellipsis, so a reader is told the record is incomplete
        // when it is not, and goes looking for the rest of an error that has no rest.
        String fits = "x".repeat(300);
        String oneMore = "x".repeat(301);

        assertEquals("the endpoint refused the request: HTTP 413 — " + fits,
                new Refused(413, fits).getMessage(),
                "300 is the last length that is kept whole");
        assertFalse(new Refused(413, fits).getMessage().contains("truncated"),
                "nothing was dropped, so nothing may claim it was");
        // ONE CHARACTER OVER IS NOT CUT, because the notice costs 33 and cutting to 300 would
        // render 334 — larger than the 301 it replaced, and missing its end. The bound is a
        // readability bound, not a hard cap, so the rule is that a cut must save something.
        assertEquals("the endpoint refused the request: HTTP 413 — " + oneMore,
                new Refused(413, oneMore).getMessage(),
                "301 is one character over and cutting it would cost 33 to save one");
    }

    @Test
    void theWholeBodyIsKeptEvenThoughTheMessageIsNot() {
        // The reason body() exists as a field rather than as a substring of getMessage(): what
        // distinguishes one refusal from another can sit past the 300th character, and a consumer
        // that has to decide whether to change its request or wait cannot be handed a summary.
        String saidByTheServer = "{\"error\":{\"message\":\""
                + "the request exceeds the context window of some-model, ".repeat(12)
                + "reduce the number of messages\"}}";

        Refused refused = new Refused(413, saidByTheServer);

        assertEquals(saidByTheServer, refused.body(), "kept whole, and it is 701 characters");
        assertTrue(refused.getMessage().length() < refused.body().length(),
                "while the message that gets written down is not");
        assertTrue(refused.body().endsWith("reduce the number of messages\"}}"),
                "including the actionable half, which is at the end and past the cut");
        assertEquals("", new Refused(500, null).body(),
                "and it is never null: a consumer asking what the server said gets a second "
                        + "failure of its own if the answer to that is a NullPointerException");
    }

    /**
     * A REFUSAL TAKEN OFF A SOCKET RATHER THAN CONSTRUCTED.
     *
     * <p>A loopback server on an ephemeral port that answers one request with a status and a body of
     * the caller's choosing. The pattern is AConsumerBringsItsOwnModelTest's, for the same reason it
     * gives: the two things being proved here — that the status is READ, and that the body arrives
     * whole — live in the wiring between {@link Wire} and this class, and every existing assertion
     * about a {@link Refused} is made on one the test built itself.
     *
     * <p>Nothing waits. The response is immediate and non-200, so the read loop and its silence
     * guard are never entered; the {@link Watch} below is present because the constructor needs one
     * and neither number is reachable from here.
     */
    private static Refused refusalFrom(int status, String said) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] refusal = said.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, refusal.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(refusal);
            }
        });
        server.start();
        try {
            Chat wire = Wire.to(
                    Endpoint.of("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "some-model"),
                    Sampling.deterministic(),
                    new Watch(Duration.ofSeconds(5), Duration.ofMinutes(1)),
                    true,
                    Trace.quiet());
            return assertThrows(Refused.class,
                    () -> wire.answer(Ask.of(List.of(Said.user("who speaks first?")))),
                    "a status other than 200 is a refusal to report, not a stream to parse");
        } finally {
            server.stop(0);
        }
    }
}
