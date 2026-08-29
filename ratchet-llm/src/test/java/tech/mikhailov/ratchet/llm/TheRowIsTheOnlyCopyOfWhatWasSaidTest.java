package tech.mikhailov.ratchet.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE ROW IS THE ONLY COPY OF WHAT WAS SAID, so a column that is quietly wrong is not wrong in one
 * place — it is the whole account of that exchange, in every corpus this project has.
 *
 * <p>{@link Listening} summarises rather than dumps, and that is the right decision: one prompt here
 * grew monotonically to 428K tokens, and writing every request whole would be that growth on disk,
 * squared over a sweep. The price of the decision is that nothing can be recovered afterwards from
 * a row that recorded the wrong thing. There is no second copy to check it against.
 *
 * <p>WHAT THE MUTATION RUN FOUND WAS ONE SHAPE REPEATED: twenty-one mutants survived in this class
 * and eight more were never reached, and almost all of them are the RIGHT-HAND half of the row.
 * {@code grep -rn '\.got()\|inTokens\|\.tools()\|\.finish()\|\.error()' ratchet-llm/src/test}
 * found five hits and not one of them was a column of an exchange row — four are {@code finish()}
 * on a thought and one is {@code ask.tools()}, the request's tool list. Every test that drove this
 * class read {@code agent()} or {@code sent()} and stopped. So what came back, what it cost, which
 * tools it asked for, why it stopped and why it failed could each have been blank or constant in all
 * 277,022 rows of a sweep, and this module would have been green.
 *
 * <p>THE ERROR ROW HAD NO COVERAGE AT ALL. {@link Listening#failed} is the row that exists BECAUSE
 * there is no answer — the third of the three things a curated record was missing — and nothing
 * called it, in this class or through {@link Wire}. The last test goes through a real socket for
 * exactly that reason: a recorder that is only ever driven by hand is a recorder whose wiring is a
 * guess, and the mutation that proved a door built with {@code Watch.fromEnv()} inside it left this
 * whole module green is one file over.
 */
class TheRowIsTheOnlyCopyOfWhatWasSaidTest {

    private static final String PROMPT = "you are a test";

    /** How long the loopback endpoint sits on each answer, so that elapsed time has a known floor. */
    private static final long HELD_MS = 300;

    @Test
    void theReplyRowCarriesWhatCameBackAndWhatItCost() {
        // THE HALF NOTHING READ. Each of these columns had a live mutant against it: tail() could
        // return "" for every answer, and the joined tool names were never reached by a test with
        // two tools in the reply.
        Written record = Written.kept();
        Ask ask = new Ask(List.of(Said.system(PROMPT), Said.user("does it build?")), List.of(),
                "agent:pins");

        new Listening(record).back(ask, new Reply("first line\nsecond line", "thought about it",
                List.of(new Called("c1", "glob", "{}"), new Called("c2", "read", "{}")),
                Ending.TOOLS, new Spend(1234, 56)), 4211);

        Trace.Exchange row = record.rows.get(0);
        assertEquals("back", row.direction());
        assertEquals(2, row.messages(), "how many messages went, not how many came back");
        assertEquals("first line\nsecond line", row.got(),
                "newlines survive: flattening them turned the one view whose job is showing what "
                        + "was said into an unreadable ribbon");
        assertEquals("glob,read", row.tools(), "which tools it asked for, all of them");
        assertEquals("TOOLS", row.finish(), "why the generation stopped");
        assertEquals(1234, row.inTokens(),
                "the thinking budget is checked against what the server SPENT, which is the "
                        + "question a character count cannot answer");
        assertEquals(56, row.outTokens());
        assertEquals(4211, row.ms());
        assertEquals("", row.sent(), "the request had its own row when it was sent");
        assertEquals("", row.error(), "an answer arrived, so nothing failed");
    }

    @Test
    void theSameToolAskedForTwiceIsOneNameInTheColumn() {
        // A parallel fan-out is the ordinary shape by the third turn, and a tools column reading
        // "glob,glob,glob,glob" is a reader counting calls when they meant to read names.
        Written record = Written.kept();

        new Listening(record).back(ask(), new Reply("", "", List.of(
                new Called("c1", "glob", "{\"pattern\":\"a\"}"),
                new Called("c2", "glob", "{\"pattern\":\"b\"}")), Ending.TOOLS, Spend.NONE), 7);

        assertEquals("glob", record.rows.get(0).tools(), "two calls, one tool");
    }

    @Test
    void everyTurnIsNamedBySpeakerAndTheAgentOwnsThePrompt() {
        // role() could return "" for every message and nothing noticed, which leaves a request
        // recorded as "[]" over each turn: the transcript with the speakers removed.
        Written record = Written.kept();

        new Listening(record).sending(new Ask(List.of(Said.system(PROMPT),
                Said.user("does the build file exist?"),
                Said.assistant("I will look", List.of(new Called("c1", "glob", "{}"))),
                Said.result(new Called("c1", "glob", "{}"), "no files match")),
                List.of(), "agent:pins"));

        String sent = record.rows.get(0).sent();
        assertTrue(sent.startsWith("[system: agent:pins, 14 chars]\nyou are a test"),
                "the prompt is written once and the header says WHOSE it is: " + sent);
        assertTrue(sent.contains("[user]\ndoes the build file exist?"), sent);
        assertTrue(sent.contains("[assistant]\nI will look"), sent);
        assertTrue(sent.contains("[tool result]\nglob -> no files match"), sent);
        assertTrue(sent.indexOf("[user]") < sent.indexOf("[assistant]")
                        && sent.indexOf("[assistant]") < sent.indexOf("[tool result]"),
                "in the order they were said, because that is the whole use of the column: " + sent);
        assertEquals(4, record.rows.get(0).messages());
    }

    @Test
    void anAssistantTurnThatIsNothingButCallsShowsTheCalls() {
        // WITHOUT THIS THE RECORD HAS A BLANK TURN FOLLOWED BY RESULTS FROM NOWHERE. The model said
        // nothing and asked for two tools; a Java toString is not an option and neither is an empty
        // line, because the next turn is the answers to calls the reader cannot see.
        Written record = Written.kept();

        new Listening(record).sending(new Ask(List.of(Said.user("does it build?"),
                Said.assistant("", List.of(new Called("c1", "glob", "{\"pattern\":\"**/*.gradle\"}"),
                        new Called("c2", "read", "{\"path\":\"build.gradle\"}")))),
                List.of(), "agent:pins"));

        assertTrue(record.rows.get(0).sent().contains("[assistant]\n"
                        + "glob{\"pattern\":\"**/*.gradle\"}\nread{\"path\":\"build.gradle\"}"),
                "both calls, named, with the arguments the model wrote, one per line: "
                        + record.rows.get(0).sent());
    }

    @Test
    void anAssistantTurnThatAlsoSaidSomethingRecordsWhatItSaid() {
        // The other side of the same condition. A turn that carries BOTH content and calls is what
        // most servers send, and preferring the calls there deletes the sentence explaining the
        // plan — the one part of that turn a person reads.
        Written record = Written.kept();

        new Listening(record).sending(new Ask(List.of(Said.user("does it build?"),
                Said.assistant("I will look at the build file first.",
                        List.of(new Called("c1", "glob", "{\"pattern\":\"**\"}")))),
                List.of(), "agent:pins"));

        assertTrue(record.rows.get(0).sent()
                        .contains("[assistant]\nI will look at the build file first."),
                record.rows.get(0).sent());
    }

    @Test
    void aResultFromANamelessCallIsNotGivenAnArrowPointingAtNothing() {
        // ratchet#9 put the tool's name in front of its answer, and the guard on it is reachable:
        // a server that streams a tool call with no function name leaves Called with an empty name,
        // and "-> no files match" reads as an answer from a tool called nothing at all.
        Written record = Written.kept();

        new Listening(record).sending(Ask.of(List.of(
                Said.result(new Called("c1", "", "{}"), "no files match"))));

        String sent = record.rows.get(0).sent();
        assertEquals("[tool result]\nno files match", sent,
                "no name, so nothing to attribute it to and no arrow");
    }

    @Test
    void nineHundredCharactersIsInsideTheBoundAndNineHundredAndOneIsNot() {
        // OFF BY ONE HERE IS A LIE ABOUT A COMPLETE MESSAGE. The boundary mutant survived, and what
        // it ships is "... (truncated)" written under a message that was recorded whole.
        Written record = Written.kept();
        Listening listening = new Listening(record);

        listening.sending(Ask.of(List.of(Said.user("x".repeat(900)))));
        listening.sending(Ask.of(List.of(Said.user("y".repeat(901)))));

        assertEquals("[user]\n" + "x".repeat(900), record.rows.get(0).sent(),
                "exactly at the bound is a whole message and must not claim to be cut");
        assertEquals("[user]\n" + "y".repeat(900) + "\n... (truncated)",
                record.rows.get(1).sent(),
                "one character over is cut AT the bound and says so, rather than being kept whole "
                        + "because the record is the thing that has to stay small");
    }

    @Test
    void theAnswerIsHeldToTheSameBoundAndSaysWhenItWasCut() {
        // tail() could return "" for every answer in the corpus and every test stayed green. These
        // two assertions are the whole of that column's contract.
        Written record = Written.kept();
        Listening listening = new Listening(record);

        listening.back(ask(), reply("z".repeat(900)), 1);
        listening.back(ask(), reply("z".repeat(901)), 1);

        assertEquals("z".repeat(900), record.rows.get(0).got(), "enough to recognise, kept whole");
        assertEquals("z".repeat(900) + "\n... (truncated)", record.rows.get(1).got(),
                "and not enough to reproduce the conversation on disk");
    }

    @Test
    void anUnlabelledPromptIsCalledUnrecognisedRatherThanLeftBlank() {
        // A consumer building an Ask by hand and not saying who it is gets an honest blank in the
        // agent column. The prompt header is different: "[system: , 6000 chars]" reads as a broken
        // writer, and a reader cannot tell it from a label that was written and lost.
        Written record = Written.kept();

        new Listening(record).sending(new Ask(List.of(Said.system(PROMPT), Said.user("go")),
                List.of(), ""));

        assertTrue(record.rows.get(0).sent().startsWith("[system: unrecognised, 14 chars]"),
                record.rows.get(0).sent());
        assertEquals("", record.rows.get(0).agent(), "and the column itself stays honestly empty");
    }

    @Test
    void aCallThatNeverGotAnAnswerIsStillARowAndSaysWhyNot() {
        // NO TEST HAD EVER CALLED THIS METHOD. It is one of the three things the curated record was
        // missing: a failure recorded where it happened, rather than a gap between a request row
        // and the next agent's first question.
        Written record = Written.kept();
        Ask ask = new Ask(List.of(Said.system(PROMPT), Said.user("go")), List.of(), "agent:pins");

        new Listening(record).failed(ask,
                new IllegalStateException("could not reach http://model:8000/v1: Connection refused"),
                9_004);

        Trace.Exchange row = record.rows.get(0);
        assertEquals("back", row.direction(), "it is the return half of an exchange that had one");
        assertEquals("agent:pins", row.agent());
        assertEquals(2, row.messages());
        assertEquals("ERROR", row.finish(),
                "not one of Ending's words, deliberately: this generation did not stop, it never ran");
        assertEquals("IllegalStateException: could not reach http://model:8000/v1: Connection "
                + "refused", row.error(), "the type AND what it said: a bare class name in this "
                + "column cannot be told from a timeout");
        assertEquals(9_004, row.ms(), "what the attempt cost in time even though it bought nothing");
        assertEquals("", row.got(), "nothing came back");
        assertEquals(0, row.inTokens(), "and nothing was spent");
    }

    @Test
    void aFailureWithNothingBehindItIsStillARow() {
        // The guard here is the recorder's own rule -- a row that cannot be written is worse than a
        // row that says little. NOTE FOR THE OWNER: the only production caller is Wire, which hands
        // this the exception it just caught and can never hand it null, so this pins a branch no
        // shipped path reaches. If the guard goes, this test goes with it.
        Written record = Written.kept();

        new Listening(record).failed(ask(), null, 12);

        assertEquals("unknown", record.rows.get(0).error(),
                "the row survives a cause it cannot describe");
    }

    @Test
    void aRecordThatCannotBeWrittenDoesNotTakeTheRunWithIt() {
        // A LISTENER THAT THROWS TAKES THE CALL WITH IT, and this one is called on the way to the
        // model. A full disk or a half-open file must cost the sweep a row, not the generation --
        // and the drop must leave a note, or a corpus is silently short of rows with nothing in it
        // saying so.
        Written broken = Written.brokenDisk();
        Listening listening = new Listening(broken);
        Ask ask = ask();

        assertDoesNotThrow(() -> listening.sending(ask));
        assertDoesNotThrow(() -> listening.back(ask, reply("ok"), 3));
        assertDoesNotThrow(() -> listening.failed(ask, new IllegalStateException("gone"), 3));

        assertEquals(List.of("listener: java.lang.IllegalStateException: the record disk is full",
                        "listener: java.lang.IllegalStateException: the record disk is full"),
                broken.notes,
                "the two rows that were dropped each left a note naming what stopped them");
        assertDoesNotThrow(() -> new Listening(Written.hopeless()).sending(ask),
                "and when even the note cannot be written there is nothing left to try: the error "
                        + "path is the last place in this library to add a second failure");
    }

    @Test
    @Timeout(60)
    void bothRowsAreWrittenByTheClientAndTheirMillisecondsAreElapsedTime() throws IOException {
        // THE WIRING, NOT THE UNIT. Wire's call to failed() had no coverage at all, so the error row
        // was reachable in production and unreachable from the tests: deleting the call left every
        // test in this module green. A loopback server stands in for the endpoint, because that
        // deletion is only visible to a test that made a request it did not build the answer to:
        // one good answer, then a refusal.
        Written record = Written.kept();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            boolean first = requests.incrementAndGet() == 1;
            // HELD DELIBERATELY, so the ms column has a floor a test can name. Without it the only
            // honest bound is ms >= 0, which a column that was always zero would pass.
            try {
                Thread.sleep(HELD_MS);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            }
            byte[] answer = (first
                    ? "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                            + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "the model is still loading").getBytes(StandardCharsets.UTF_8);
            if (first) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            }
            exchange.sendResponseHeaders(first ? 200 : 503, answer.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(answer);
            }
        });
        server.start();
        try {
            Chat client = Wire.to(
                    Endpoint.of("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "some-model"),
                    Sampling.deterministic(), new Watch(Duration.ofSeconds(5), Duration.ofMinutes(1)),
                    true, record);

            assertEquals("ok", client.answer(ask()).said());
            Refused refused = assertThrows(Refused.class, () -> client.answer(ask()));
            assertEquals(503, refused.status());
        } finally {
            server.stop(0);
        }

        List<Trace.Exchange> rows = record.rows;
        assertEquals(4, rows.size(), "a request row and a return row for each call");
        assertEquals(List.of("to", "back", "to", "back"),
                rows.stream().map(Trace.Exchange::direction).toList(),
                "and in THAT order: the request is filed when it is sent, not when the answer comes "
                        + "back, or a seventeen-second call files its own prompt after the reasoning "
                        + "it caused and the record reads backwards");
        assertEquals("ok", rows.get(1).got(), "what the endpoint actually said");
        assertEquals("ERROR", rows.get(3).finish(),
                "the call that never produced an answer is a row rather than a gap");
        assertTrue(rows.get(3).error().startsWith("Refused: "), rows.get(3).error());
        assertTrue(rows.get(3).error().contains("HTTP 503"),
                "the status is the whole diagnosis for a refusal: " + rows.get(3).error());
        assertTrue(rows.get(1).ms() >= HELD_MS && rows.get(1).ms() < 60_000,
                "ms is time ELAPSED over this call, and the endpoint held the answer for "
                        + HELD_MS + "ms, so the row cannot be below that. A sweep sums this column: "
                        + "a wall-clock reading there is a thousand years per call and a constant "
                        + "is a sweep that took no time at all: " + rows.get(1).ms());
        assertTrue(rows.get(3).ms() >= HELD_MS && rows.get(3).ms() < 60_000,
                "including the row for the call that failed, which is the one nothing reached: "
                        + rows.get(3).ms());
    }

    private static Ask ask() {
        return new Ask(List.of(Said.system(PROMPT), Said.user("go")), List.of(), "agent:pins");
    }

    private static Reply reply(String said) {
        return new Reply(said, "", List.of(), Ending.STOPPED, Spend.NONE);
    }

    /**
     * One trace with three behaviours: it keeps what it is given, or the write fails, or everything
     * fails.
     *
     * <p>Written once as a class rather than three times as anonymous ones. {@link Trace#quiet()}
     * exists because fourteen test files in this project implemented nine empty methods each to say
     * "no thank you", and it cannot be used here — this file is asserting what was recorded.
     */
    private static final class Written implements Trace {

        private final List<Trace.Exchange> rows = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private final boolean writingFails;
        private final boolean notingFails;

        private Written(boolean writingFails, boolean notingFails) {
            this.writingFails = writingFails;
            this.notingFails = notingFails;
        }

        static Written kept() {
            return new Written(false, false);
        }

        /** The row cannot be written, but the reason for that can. */
        static Written brokenDisk() {
            return new Written(true, false);
        }

        /** Nothing can be written, including the note saying so. */
        static Written hopeless() {
            return new Written(true, true);
        }

        @Override
        public void exchanged(Exchange exchange) {
            if (writingFails) {
                throw new IllegalStateException("the record disk is full");
            }
            rows.add(exchange);
        }

        @Override
        public void progress(String key, String note) {
            if (notingFails) {
                throw new IllegalStateException("and so is the one next to it");
            }
            notes.add(note);
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
        public void thought(String finishReason, String thinking, String content) {
        }

        @Override
        public void built(String phase, Outcome result) {
        }

        @Override
        public void settled(String key, String state, String because, boolean beforeOk,
                            boolean afterOk) {
        }

        @Override
        public void failed(String key, Throwable cause) {
        }

        @Override
        public void priced(String key, String minutes, String itemisation) {
        }
    }
}
