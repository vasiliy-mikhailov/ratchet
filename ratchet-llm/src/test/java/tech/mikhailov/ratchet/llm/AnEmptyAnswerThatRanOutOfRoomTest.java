package tech.mikhailov.ratchet.llm;

import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;

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
 * <p>The model spent the entire budget reasoning and wrote nothing. {@code finishReason()} has been
 * on the response the whole time and nothing read it, so that empty string was returned as the
 * agent's answer — and a consumer that writes what the agent returned wrote nothing over a file that
 * had something in it.
 *
 * <p>It matters that this is neither of the two things it resembles. Handed to {@link Insisting} it
 * is read as SILENCE and the model is re-asked, which spends a second full budget arriving at the
 * same wall. Handed to {@link Retrying} it would be read as TRANSIENT and asked ten more times, for
 * ten more full generations and the same empty answer.
 */
class AnEmptyAnswerThatRanOutOfRoomTest {

    @Test
    void anEmptyAnswerThatRanOutOfRoomIsRefusedRatherThanReturned() {
        Streamed streamed = new Streamed(ended(FinishReason.LENGTH, ""), QUIET);

        Streamed.Truncated cut = assertThrows(Streamed.Truncated.class,
                () -> streamed.chat(ask()));

        assertTrue(cut.getMessage().contains("token limit"), cut.getMessage());
        assertTrue(cut.getMessage().contains("RATCHET_THINKING_TOKENS"),
                "and it says what to change, because the reader is mid-sweep: " + cut.getMessage());
    }

    @Test
    void anAnswerThatRanOutOfRoomIsStillAnAnswer() {
        // Truncated PROSE is a real reply that got cut off. The caller may still want it, and
        // refusing it would throw away work the model actually did.
        Streamed streamed = new Streamed(ended(FinishReason.LENGTH, "Pierre Bezúkhov is the"), QUIET);

        assertEquals("Pierre Bezúkhov is the", streamed.chat(ask()).aiMessage().text());
    }

    @Test
    void anEmptyAnswerThatSIMPLYSTOPPEDIsSilenceAndNotATruncation() {
        // The distinction the type exists for. A model that declined to answer finishes with STOP,
        // and that is Insisting's business — re-asking it is exactly right.
        Streamed streamed = new Streamed(ended(FinishReason.STOP, ""), QUIET);

        assertEquals("", streamed.chat(ask()).aiMessage().text(),
                "silence passes through to the layer that judges silence");
    }

    @Test
    void aTruncationIsNotRetried() {
        // The identical request meets the identical budget. Ten attempts buy ten more full
        // generations and the same empty answer.
        assertFalse(Retrying.transportFailures().test(new Streamed.Truncated("no room left")),
                "retrying this is paying ten times to hit one wall");
    }

    @Test
    void aTruncationIsNotConfusedWithTheCeilingOrWithSilence() {
        assertFalse(Retrying.transportFailures().test(new Streamed.GaveUp("3h")),
                "the ceiling is also not retried, for a different reason");
        assertTrue(Retrying.transportFailures().test(new IllegalStateException("connection reset")),
                "and an ordinary drop still is");
    }

    // ---------------------------------------------------------------- the fakes

    private static ChatRequest ask() {
        return ChatRequest.builder().messages(UserMessage.from("who speaks first?")).build();
    }

    /** A stream that completes once, with the finish reason and content given. */
    private static StreamingChatModel ended(FinishReason why, String content) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(content))
                        .metadata(ChatResponseMetadata.builder().finishReason(why).build())
                        .build());
            }
        };
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
