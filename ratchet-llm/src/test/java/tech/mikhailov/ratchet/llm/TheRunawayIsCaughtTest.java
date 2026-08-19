package tech.mikhailov.ratchet.llm;

import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Greedy decoding cannot leave a repetition cycle it has entered: 14 trapped generations were
 * restarted with 2500 more tokens and none escaped. The budget is spent either way, so the only
 * question is whether we keep paying. Six repeats of a substantial line caught 66 of 68 real
 * runaways at a median 27% of the reasoning, against 4 false positives in 824 healthy turns.
 */
class TheRunawayIsCaughtTest {

    private static final class Recorder implements Trace {
        final List<String> finishes = new ArrayList<>();

        public void thought(String f, String t, String c) {
            finishes.add(f);
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

    private static ServerSentEventListener listener(Trace t) throws Exception {
        var ctor = Class.forName("tech.mikhailov.ratchet.llm.Reasoning$Accumulating")
                .getDeclaredConstructor(ServerSentEventListener.class, Trace.class);
        ctor.setAccessible(true);
        return (ServerSentEventListener) ctor.newInstance(new ServerSentEventListener() {
            public void onError(Throwable e) {
            }
        }, t);
    }

    private static ServerSentEvent chunk(String reasoning) {
        return new ServerSentEvent(null,
                "{\"choices\":[{\"delta\":{\"reasoning\":\"" + reasoning + "\"}}]}");
    }

    @Test
    void aRepeatedLineAbortsTheGeneration() throws Exception {
        Recorder r = new Recorder();
        var l = listener(r);
        // The real shape: an answer reached early, then the same substantial line forever.
        String line = "Let me check each proactive trigger against the build files in turn now.\\n";
        assertThrows(RuntimeException.class, () -> {
            for (int i = 0; i < 40; i++) {
                l.onEvent(chunk(line));
            }
        }, "a cycle must abort rather than run to the token ceiling");
        assertTrue(r.finishes.contains("loop"), "and be recorded as a loop, not as length");
    }

    @Test
    void aDetectionFiresExactlyOnceEvenIfTheStreamKeepsComing() throws Exception {
        // Throwing out of the listener does not reliably cancel the stream. When it does not, every
        // later line re-trips the already-satisfied counter: one real detection became 971 rows for
        // a single run, and 9,916 across one corpus against 776 normal finishes.
        Recorder r = new Recorder();
        var l = listener(r);
        String line = "Let me check each proactive trigger against the build files in turn now.\\n";
        try {
            for (int i = 0; i < 40; i++) {
                l.onEvent(chunk(line));
            }
        } catch (RuntimeException expected) {
            // the first detection
        }
        // The client ignored it and kept delivering, as it does in practice.
        for (int i = 0; i < 200; i++) {
            try {
                l.onEvent(chunk(line));
            } catch (RuntimeException ignored) {
                throw new AssertionError("a latched detector must not throw again");
            }
        }
        l.onClose();
        assertEquals(1, r.finishes.size(), "one row per response, not one per line");
        assertEquals("loop", r.finishes.get(0));
    }

    @Test
    void ordinaryReasoningIsNotMistakenForALoop() throws Exception {
        Recorder r = new Recorder();
        var l = listener(r);
        for (int i = 0; i < 60; i++) {
            l.onEvent(chunk("Considering trigger number " + i
                    + ", which is a distinct substantial line of reasoning about this project.\\n"));
        }
        l.onClose();
        assertEquals(1, r.finishes.size(), "one thought at the end, no abort");
    }

    @Test
    void shortRepeatedLinesAreHabitsNotCycles() throws Exception {
        Recorder r = new Recorder();
        var l = listener(r);
        for (int i = 0; i < 30; i++) {
            l.onEvent(chunk("Let me think.\\n"));
        }
        l.onClose();
        assertTrue(r.finishes.size() <= 1, "a short refrain must not trip the detector");
    }
}
