package tech.mikhailov.ratchet.llm;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A TRUNCATION IS NOT AN ANSWER AND IT IS NOT SILENCE.
 *
 * <p>Measured against this project's own endpoint before any of this was written. One prompt, no
 * reasoning budget, a 3,000-token completion cap:
 *
 * <pre>
 * thinking 9,488 chars   content 0 chars   finish_reason: length
 * </pre>
 *
 * <p>The model spent the entire budget reasoning and wrote nothing. The finish reason has been on
 * the response the whole time and nothing read it, so that empty string was returned as the agent's
 * answer — and a consumer that writes what the agent returned wrote nothing over a file that had
 * something in it. It is {@link Wire#read} that reads it now: the reason arrives as
 * {@code "finish_reason":"length"} on a frame this test can simply hand it, where before it took a
 * client library's response object to say the same thing.
 *
 * <p>It matters that this is neither of the two things it resembles. Handed to {@link Insisting} it
 * is read as SILENCE and the model is re-asked, which spends a second full budget arriving at the
 * same wall. Handed to {@link Retrying} it would be read as TRANSIENT and asked ten more times, for
 * ten more full generations and the same empty answer.
 */
class AnEmptyAnswerThatRanOutOfRoomTest {

    @Test
    void anEmptyAnswerThatRanOutOfRoomIsRefusedRatherThanReturned() {
        Truncated cut = assertThrows(Truncated.class, () -> client().read(
                ended("length", "The question is who speaks first in the novel, so I should ", "")));

        assertTrue(cut.getMessage().contains("token limit"), cut.getMessage());
        assertTrue(cut.getMessage().contains("RATCHET_THINKING_TOKENS"),
                "and it says what to change, because the reader is mid-sweep: " + cut.getMessage());
    }

    @Test
    void anAnswerThatRanOutOfRoomIsStillAnAnswer() {
        // Truncated PROSE is a real reply that got cut off. The caller may still want it, and
        // refusing it would throw away work the model actually did.
        Reply reply = client().read(ended("length", "", "Pierre Bezúkhov is the"));

        assertEquals("Pierre Bezúkhov is the", reply.said());
        assertEquals(Ending.LENGTH, reply.ending(),
                "and the reply says it was cut off, which is the caller's to judge");
    }

    @Test
    void anEmptyAnswerThatSIMPLYSTOPPEDIsSilenceAndNotATruncation() {
        // The distinction the type exists for. A model that declined to answer finishes with STOP,
        // and that is Insisting's business — re-asking it is exactly right.
        Reply reply = client().read(ended("stop", "", ""));

        assertEquals("", reply.said(), "silence passes through to the layer that judges silence");
        assertEquals(Ending.STOPPED, reply.ending());
    }

    @Test
    void anEmptyAnswerThatRanOutOfRoomASKINGFORATOOLIsNotEmptyEITHER() {
        // The third shape, visible only now that this library parses the frames itself: blank
        // content, finish_reason length, and a tool call in the turn. There IS something to act on,
        // so the guard requires the call list to be empty as well before it refuses the reply.
        Reply reply = client().read(Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"t1\","
                        + "\"type\":\"function\",\"index\":0,\"function\":{\"name\":\"read_file\"}}]},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}",
                "",
                "data: [DONE]"));

        assertTrue(reply.wantsTools(), "the turn asked for something; that is not silence");
        assertEquals("read_file", reply.calls().get(0).name());
        assertEquals(Ending.LENGTH, reply.ending(), "and it still says the budget ran out");
    }

    @Test
    void aTruncationIsNotRetried() {
        // The identical request meets the identical budget. Ten attempts buy ten more full
        // generations and the same empty answer.
        assertFalse(Retrying.transportFailures().test(new Truncated("no room left")),
                "retrying this is paying ten times to hit one wall");
    }

    @Test
    void aTruncationIsNotConfusedWithTheCeilingOrWithSilence() {
        assertFalse(Retrying.transportFailures().test(new GaveUp("3h")),
                "the ceiling is also not retried, for a different reason");
        assertTrue(Retrying.transportFailures().test(new IllegalStateException("connection reset")),
                "and an ordinary drop still is");
    }

    // ---------------------------------------------------------------- the fakes

    /**
     * A client with no socket under it. Everything the guard reads is a value now — the patience,
     * the sampling, the endpoint — so the frames can be handed straight to {@link Wire#read}.
     */
    private static Wire client() {
        return new Wire(Endpoint.of("http://localhost:1", "a-model"), Sampling.deterministic(),
                Watch.shipped(), true, QUIET);
    }

    /**
     * One generation, in the frame shapes captured from the production endpoint: the opening role
     * delta, whatever it thought and said, the finish reason, then the usage chunk whose
     * {@code choices} array is empty, then {@code [DONE]}.
     */
    private static Stream<String> ended(String why, String reasoning, String content) {
        return Stream.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                        + "\"content\":\"\"},\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"" + reasoning + "\"},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},"
                        + "\"finish_reason\":\"" + why + "\"}]}",
                "",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":57,\"total_tokens\":69,"
                        + "\"completion_tokens\":12}}",
                "",
                "data: [DONE]");
    }

    private static final Trace QUIET = new Trace() {
        public void asked(String a, String p, String r) { }
        public void progress(String k, String n) { }
        public void applied(String s, String w) { }
        public void tool(String a, String t, String g, String r) { }
        public void thought(String f, String t, String c) { }
        public void built(String p, Trace.Outcome r) { }
        public void settled(String k, String s, String w, boolean b, boolean a) { }
        public void failed(String k, Throwable c) { }
        public void priced(String k, String m, String i) { }
    };
}
