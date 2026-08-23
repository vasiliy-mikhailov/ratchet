package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SERVER CALLS THE FIELD {@code reasoning}; THE OLD CLIENT LOOKED FOR
 * {@code reasoning_content}.
 *
 * <p>Eighty-four calls produced eighty-four blanks before the body was read directly, so this pins
 * the field names and the mid-thought case that motivates recording them at all.
 *
 * <p>WHAT MOVED. Recovering the reasoning used to take a transport interceptor wrapped around
 * somebody else's SSE parser, and this file used to reach the accumulator inside it by reflection,
 * {@code Class.forName("...Reasoning$Accumulating")}, because that was the only door there was.
 * {@link Wire} owns the parse now: it reads BOTH field names off the frame, puts the text on
 * {@link Reply#reasoning()} and hands the deltas to the {@link Reasoning} accumulator, which is
 * still what writes the one {@link Trace#thought} row per response. So the frames go in the front
 * door here, through the same {@code read} the socket feeds, and the assertions are unchanged: the
 * server's field name, the client's field name, one thought per response rather than one per token,
 * and an answer cut off mid-thought recorded as such.
 */
class TheReasoningIsNotDiscardedTest {

    private record Thought(String finish, String thinking, String content) {
    }

    @Test
    void theServersFieldNameIsRead() {
        Recorder r = new Recorder();

        Reply reply = client(r).read(frames(
                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},"
                        + "\"finish_reason\":null}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"The parent pins 11, and "
                        + "nothing overrides it.\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"sound\"},"
                        + "\"finish_reason\":\"stop\"}]}"));

        assertEquals(1, r.thoughts.size());
        assertEquals("stop", r.thoughts.get(0).finish());
        assertTrue(r.thoughts.get(0).thinking().contains("nothing overrides it"));
        assertEquals("sound", r.thoughts.get(0).content());
        // And the caller gets it too, which is the part that used to be impossible: the reasoning
        // was generated, paid for and dropped inside the client before anything could ask for it.
        assertTrue(reply.reasoning().contains("nothing overrides it"), reply.reasoning());
    }

    @Test
    void theClientsFieldNameAlsoWorks() {
        Recorder r = new Recorder();

        Reply reply = client(r).read(frames(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\","
                        + "\"reasoning_content\":\"weighed it\"},\"finish_reason\":\"stop\"}]}"));

        assertEquals("weighed it", r.thoughts.get(0).thinking());
        assertEquals("weighed it", reply.reasoning(),
                "both spellings are read, because which one a server uses is not ours to decide");
    }

    @Test
    void anAnswerCutOffMidThoughtIsRecordedAsSuch() {
        // The case that matters: all budget spent thinking, nothing left to answer with. Without
        // this the empty content reaches a word list and is read as its default, which approves.
        // The empty answer is now REFUSED as well as recorded (AnEmptyAnswerThatRanOutOfRoom), and
        // the row is written first, so the refusal never costs the diagnosis.
        Recorder r = new Recorder();

        assertThrows(Truncated.class, () -> client(r).read(frames(
                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"Let me consider each "
                        + "trigger in turn...\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},"
                        + "\"finish_reason\":\"length\"}]}")));

        assertEquals(1, r.thoughts.size(), "the row goes in before the throw, not instead of it");
        assertEquals("length", r.thoughts.get(0).finish());
        assertEquals("", r.thoughts.get(0).content());
        assertTrue(r.thoughts.get(0).thinking().startsWith("Let me consider"));
    }

    @Test
    void theStreamedDeltasAreAccumulatedIntoOneThought() {
        // Real chunks from this server: the reasoning arrives a token at a time under `reasoning`,
        // which was the field the old client did not read, so onPartialThinking never fired. It is
        // read here, one delta at a time, and a trace of ten thousand one-token rows is not a
        // record of anything — so they are accumulated and emitted once.
        Recorder r = new Recorder();

        Reply reply = client(r).read(frames(
                "{\"choices\":[{\"delta\":{\"reasoning\":\"Here\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"delta\":{\"reasoning\":\" we go\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"sound\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"));

        assertEquals(1, r.thoughts.size(), "one thought per response, not one per token");
        assertEquals("Here we go", r.thoughts.get(0).thinking());
        assertEquals("sound", r.thoughts.get(0).content());
        assertEquals("stop", r.thoughts.get(0).finish());
        assertEquals("Here we go", reply.reasoning(), "and the same text reaches the caller whole");
        assertEquals("sound", reply.said());
    }

    @Test
    void anAnswerWithNoReasoningRecordsNothing() {
        Recorder r = new Recorder();

        client(r).read(frames("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"plain\"},"
                + "\"finish_reason\":\"stop\"}]}"));
        // Nothing parseable at all: no frames, only the marker that ends the body. The old entry
        // point took a response body and was handed "" and null here; the equivalent now is a
        // stream that says nothing, and it must be as quiet as those were.
        client(r).read(Stream.of("data: [DONE]"));

        assertTrue(r.thoughts.isEmpty(), "an answer that did no thinking has no thought to record");
    }

    @Test
    void anAnswerWithNothingInItAtAllDoesLeaveARow() {
        // The one carve-out in the rule above, and the reason it is here is that it is a rule with
        // an exception rather than a rule: an empty with no row at all is an empty nobody can
        // diagnose, and only 75 of 182 empties were visible as `length` before this was written.
        Recorder r = new Recorder();

        client(r).read(frames("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},"
                + "\"finish_reason\":\"stop\"}]}"));

        assertEquals(1, r.thoughts.size(), "nothing said and nothing thought is the finding");
        assertEquals("stop", r.thoughts.get(0).finish(), "and the row says how it ended");
    }

    // ---------------------------------------------------------------- the fakes

    /**
     * A client with no socket under it, so frames can be handed straight to {@link Wire#read}.
     *
     * <p>Everything the read loop consults is a value now — the patience, the sampling, the
     * endpoint, the trace — which is what makes the reasoning path assertable without a server
     * and without reflecting into a nested class.
     */
    private static Wire client(Trace trace) {
        return new Wire(Endpoint.of("http://localhost:1", "a-model"), Sampling.deterministic(),
                Watch.shipped(), true, trace);
    }

    /**
     * One generation as SSE lines, in the shape captured from the production endpoint: each frame
     * on a {@code data:} line, a blank line between frames, and {@code [DONE]} at the end.
     */
    private static Stream<String> frames(String... chunks) {
        List<String> lines = new ArrayList<>();
        for (String chunk : chunks) {
            lines.add("data: " + chunk);
            lines.add("");
        }
        lines.add("data: [DONE]");
        return lines.stream();
    }

    private static final class Recorder implements Trace {
        final List<Thought> thoughts = new ArrayList<>();

        public void thought(String f, String t, String c) {
            thoughts.add(new Thought(f, t, c));
        }

        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String k, String s, String w, boolean before, boolean after) {
        }

        public void failed(String k, Throwable c) {
        }

        public void progress(String k, String n) {
        }

        public void priced(String k, String m, String i) {
        }
    }
}
