package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CYCLE THE MODEL CANNOT LEAVE IS ENDED HERE, NOT PAID FOR UP TO THE CEILING.
 *
 * <p>Greedy decoding cannot leave a repetition cycle it has entered: 14 trapped generations were
 * restarted with 2500 more tokens and none escaped. The budget is spent either way, so the only
 * question is whether we keep paying. Six repeats of a substantial line caught 66 of 68 real
 * runaways at a median 27% of the reasoning, against 4 false positives in 824 healthy turns.
 *
 * <p>WHAT MOVED. The detector used to live in an SSE listener wrapped around somebody else's parser,
 * and this file reached it by reflection — {@code Class.forName("...Reasoning$Accumulating")} —
 * because a transport interceptor was the only place it could sit. {@link Wire} owns the parse now
 * and hands the reasoning deltas to the {@link Reasoning} accumulator, so the frames go in the front
 * door here, through the same {@code read} the socket feeds. What the detection is worth is
 * unchanged: the generation aborts, the row says {@code loop}, and ordinary thinking is left alone.
 *
 * <p>AND THE THROW NOW ARRIVES SOMEWHERE. {@link Reasoning.LoopDetected} leaves {@code read}
 * deliberately, so the body is closed and the server stops generating; the client this replaces
 * swallowed it inside its own listener, where it ended the recording and nothing else. What happens
 * after it is {@link Retrying}'s to decide, and it decides no — asserted in
 * {@code AFlakyEndpointIsAskedAgainTest}, which could not have been written at all before.
 */
class TheRunawayIsCaughtTest {

    @Test
    void aRepeatedLineAbortsTheGeneration() {
        Recorder r = new Recorder();
        // The real shape: an answer reached early, then the same substantial line forever.
        String line = "Let me check each proactive trigger against the build files in turn now.\\n";

        Reasoning.LoopDetected caught = assertThrows(Reasoning.LoopDetected.class,
                () -> client(r).read(repeating(line, 40)),
                "a cycle must abort rather than run to the token ceiling");

        assertTrue(caught.getMessage().contains("repeated a line"), caught.getMessage());
        assertTrue(r.finishes.contains("loop"), "and be recorded as a loop, not as length");
        assertTrue(r.thinking.get(0).contains("each proactive trigger"),
                "with the reasoning kept, because which line trapped it is the whole diagnosis");
        // Six, and the thirty-four frames behind it never read. Not one row per response — that is
        // the latch, asserted below — but the saving itself: a detection at the fortieth repeat
        // costs what running to the ceiling costs, and the median catch was measured at 27% of the
        // reasoning. Fire-once cannot be shown here, because through Wire the throw ends the loop.
        assertEquals(6, r.thinking.get(0).split("\n", -1).length - 1,
                "at the sixth repeat, not whenever the stream happened to end");
    }

    @Test
    void aDetectionFiresExactlyOnceEvenIfTheStreamKeepsComing() {
        // Throwing out of the old listener did not reliably cancel the stream. When it did not, every
        // later line re-tripped the already-satisfied counter: one real detection became 971 rows for
        // a single run, and 9,916 across one corpus against 776 normal finishes. Ratchet's own read
        // loop does end the body on the throw — which is why this test drives the accumulator
        // directly, as through Wire the stream is already over — but the latch is what makes the
        // promise "one row per response" instead of "one row per response, if the transport agrees".
        Recorder r = new Recorder();
        Reasoning watching = new Reasoning(r, "doer");
        String line = "Let me check each proactive trigger against the build files in turn now.\n";
        try {
            for (int i = 0; i < 40; i++) {
                watching.reasoned(line);
            }
        } catch (Reasoning.LoopDetected expected) {
            // the first detection
        }
        // The deltas keep arriving, as they did when the client ignored the throw.
        for (int i = 0; i < 200; i++) {
            try {
                watching.reasoned(line);
            } catch (Reasoning.LoopDetected again) {
                throw new AssertionError("a latched detector must not throw again");
            }
        }
        watching.ended("stop");
        watching.flush();

        assertEquals(1, r.finishes.size(), "one row per response, not one per line");
        assertEquals("loop", r.finishes.get(0),
                "and a finish reason arriving afterwards does not overwrite why it really ended");
    }

    @Test
    void ordinaryReasoningIsNotMistakenForALoop() {
        Recorder r = new Recorder();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            lines.add("Considering trigger number " + i
                    + ", which is a distinct substantial line of reasoning about this project.\\n");
        }

        Reply reply = client(r).read(thinking(lines));

        assertEquals(1, r.finishes.size(), "one thought at the end, no abort");
        assertEquals("stop", r.finishes.get(0), "and it ended the way the server said it did");
        assertEquals(Ending.STOPPED, reply.ending());
    }

    @Test
    void shortRepeatedLinesAreHabitsNotCycles() {
        Recorder r = new Recorder();

        Reply reply = client(r).read(repeating("Let me think.\\n", 30));

        assertTrue(r.finishes.size() <= 1, "a short refrain must not trip the detector");
        assertFalse(r.finishes.contains("loop"), "a habit is not a cycle: " + r.finishes);
        assertEquals(Ending.STOPPED, reply.ending(), "the generation was left to finish");
    }

    // ---------------------------------------------------------------- the fakes

    /**
     * A client with no socket under it, so frames can be handed straight to {@link Wire#read}.
     *
     * <p>Everything the read loop consults is a value now — the patience, the sampling, the
     * endpoint, the trace — which is what makes the detector assertable without a server and
     * without reflecting into a nested class.
     */
    private static Wire client(Trace trace) {
        return new Wire(Endpoint.of("http://localhost:1", "a-model"), Sampling.deterministic(),
                Watch.shipped(), true, trace);
    }

    /** The same reasoning line, that many times, in one generation. */
    private static Stream<String> repeating(String line, int times) {
        return thinking(Collections.nCopies(times, line));
    }

    /**
     * One generation that thinks these lines and then answers, in the frame shapes captured from the
     * production endpoint: one delta per line under the server's own {@code reasoning} field, the
     * finish reason on the last content frame, then the usage chunk whose {@code choices} array is
     * empty. Each line carries its own {@code \n}, which is what the detector splits on.
     */
    private static Stream<String> thinking(List<String> lines) {
        List<String> chunks = new ArrayList<>();
        chunks.add("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},"
                + "\"finish_reason\":null}]}");
        for (String line : lines) {
            chunks.add("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"" + line + "\"},"
                    + "\"finish_reason\":null}]}");
        }
        chunks.add("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\\n\\nok\"},"
                + "\"finish_reason\":\"stop\"}]}");
        chunks.add("{\"choices\":[],\"usage\":{\"prompt_tokens\":57,\"total_tokens\":69,"
                + "\"completion_tokens\":12}}");
        return frames(chunks.toArray(new String[0]));
    }

    /** Each frame on a {@code data:} line, a blank line between frames, {@code [DONE]} at the end. */
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
        final List<String> finishes = new ArrayList<>();
        final List<String> thinking = new ArrayList<>();

        public void thought(String agent, String f, String t, String c) {
            finishes.add(f);
            thinking.add(t);
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

        public void failed(String agent, String k, Throwable c) {
        }

        public void progress(String k, String n) {
        }

        public void priced(String k, String m, String i) {
        }
    }
}
