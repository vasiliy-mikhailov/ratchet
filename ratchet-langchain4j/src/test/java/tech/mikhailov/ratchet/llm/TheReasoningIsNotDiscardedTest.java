package tech.mikhailov.ratchet.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server calls the field `reasoning`; the client looks for `reasoning_content`. Eighty-four
 * calls produced eighty-four blanks before the body was read directly, so this pins the field
 * names and the mid-thought case that motivates recording them at all.
 */
class TheReasoningIsNotDiscardedTest {

    private record Thought(String finish, String thinking, String content) {
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

    @Test
    void theServersFieldNameIsRead() {
        Recorder r = new Recorder();
        Reasoning.record(r, """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                 "content":"sound","reasoning":"The parent pins 11, and nothing overrides it."}}]}
                """);
        assertEquals(1, r.thoughts.size());
        assertEquals("stop", r.thoughts.get(0).finish());
        assertTrue(r.thoughts.get(0).thinking().contains("nothing overrides it"));
        assertEquals("sound", r.thoughts.get(0).content());
    }

    @Test
    void theClientsFieldNameAlsoWorks() {
        Recorder r = new Recorder();
        Reasoning.record(r, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                + "{\"content\":\"ok\",\"reasoning_content\":\"weighed it\"}}]}");
        assertEquals("weighed it", r.thoughts.get(0).thinking());
    }

    @Test
    void anAnswerCutOffMidThoughtIsRecordedAsSuch() {
        // The case that matters: all budget spent thinking, nothing left to answer with. Without
        // this the empty content reaches a word list and is read as its default, which approves.
        Recorder r = new Recorder();
        Reasoning.record(r, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":"
                + "{\"content\":\"\",\"reasoning\":\"Let me consider each trigger in turn...\"}}]}");
        assertEquals("length", r.thoughts.get(0).finish());
        assertEquals("", r.thoughts.get(0).content());
        assertTrue(r.thoughts.get(0).thinking().startsWith("Let me consider"));
    }

    @Test
    void theStreamedDeltasAreAccumulatedIntoOneThought() throws Exception {
        // Real chunks from this server: the reasoning arrives a token at a time under `reasoning`,
        // which is the field the client does not read, so onPartialThinking never fires.
        Recorder r = new Recorder();
        var ctor = Class.forName("tech.mikhailov.ratchet.llm.Reasoning$Accumulating")
                .getDeclaredConstructor(
                        Class.forName("dev.langchain4j.http.client.sse.ServerSentEventListener"),
                        Trace.class);
        ctor.setAccessible(true);
        var listener = (dev.langchain4j.http.client.sse.ServerSentEventListener) ctor.newInstance(
                new dev.langchain4j.http.client.sse.ServerSentEventListener() {
                    public void onError(Throwable t) {
                    }
                }, r);
        for (String chunk : new String[]{
                "{\"choices\":[{\"delta\":{\"reasoning\":\"Here\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"delta\":{\"reasoning\":\" we go\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"sound\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]"}) {
            listener.onEvent(new dev.langchain4j.http.client.sse.ServerSentEvent(null, chunk));
        }
        listener.onClose();

        assertEquals(1, r.thoughts.size(), "one thought per response, not one per token");
        assertEquals("Here we go", r.thoughts.get(0).thinking());
        assertEquals("sound", r.thoughts.get(0).content());
        assertEquals("stop", r.thoughts.get(0).finish());
    }

    @Test
    void aBodyWithNoReasoningRecordsNothing() {
        Recorder r = new Recorder();
        Reasoning.record(r, "{\"choices\":[{\"message\":{\"content\":\"plain\"}}]}");
        Reasoning.record(r, "");
        Reasoning.record(r, null);
        assertTrue(r.thoughts.isEmpty());
    }
}
