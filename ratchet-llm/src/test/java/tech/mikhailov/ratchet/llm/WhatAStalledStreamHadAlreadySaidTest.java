package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
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
 * A STALLED GENERATION'S TOKENS ARE THE DIAGNOSIS, AND THEY WERE BEING DROPPED ON ARRIVAL.
 *
 * <p>Reported as ratchet#7 by a consumer who went to delete their own 173-line stall guard in favour
 * of this one and could not, for two reasons. Both were seams stopping one step short of the package
 * boundary, which is the fourth time — {@code Backoff}/{@code Pause} before 0.8.0, the clock before
 * 0.9.0, the endpoint before 0.10.0.
 *
 * <p>The second reason was the sharper one and worse than reported. {@code Streamed} took a
 * {@link Trace} and never wrote to it — {@code grep -n "trace\."} returned nothing — but it also
 * never KEPT the tokens: {@code onPartialResponse} took the text and used it only to stamp a clock.
 * So on a stall there was nothing to record even had it wanted to, and the only evidence of a
 * three-hour lane was that it had lasted three hours.
 */
class WhatAStalledStreamHadAlreadySaidTest {

    @Test
    void aStalledStreamPutsWhatItSaidIntoTheRecordBeforeThrowing() {
        Notes notes = new Notes();
        // Says two things, then goes quiet for ever. The stall bound is milliseconds here, which is
        // only possible because Watch is a value now — this is the test the constants forbade.
        ChatModel guarded = Streamed.over(saysThenStalls("Let me think about ", "whether Napoleon "),
                notes, new Watch(Duration.ofMillis(1), Duration.ofMinutes(5)));

        IllegalStateException stalled = assertThrows(IllegalStateException.class,
                () -> guarded.chat(ask()));

        assertTrue(stalled.getMessage().contains("not producing"), stalled.getMessage());
        assertEquals(1, notes.thoughts.size(), "the tokens reached the record: " + notes.thoughts);
        assertTrue(notes.thoughts.get(0).contains("Let me think about whether Napoleon "),
                "and they are what the stream actually said: " + notes.thoughts.get(0));
    }

    @Test
    void aTruncationRecordsTheReasoningThatSpentTheBudget() {
        // The case that argues hardest for keeping them: Truncated exists precisely BECAUSE the
        // answer is blank, so what was produced before it is the only thing there is to look at.
        Notes notes = new Notes();
        ChatModel guarded = Streamed.over(saysThenEnds(FinishReason.LENGTH, "",
                "the Bourbons fled from the Revolution"), notes, Watch.shipped());

        assertThrows(Streamed.Truncated.class, () -> guarded.chat(ask()));

        assertEquals(1, notes.thoughts.size());
        assertTrue(notes.thoughts.get(0).contains("the Bourbons fled"), notes.thoughts.get(0));
    }

    @Test
    void aCallThatWorksRecordsNothingExtra() {
        Notes notes = new Notes();
        ChatModel guarded = Streamed.over(saysThenEnds(FinishReason.STOP, "an answer", "an answer"),
                notes, Watch.shipped());

        assertEquals("an answer", guarded.chat(ask()).aiMessage().text());
        assertEquals(0, notes.thoughts.size(), "nothing failed, so there is nothing to diagnose");
    }

    @Test
    void theTwoBoundsAreValuesRatherThanClassInitConstants() {
        // ratchet#7: a settings page writes a file and every call re-reads it, so patience can
        // change while a 350-marker sweep runs. Restarting a container to change an environment
        // variable kills the pool and orphans every claim in flight.
        Watch patient = Watch.shipped().withStall(Duration.ofMinutes(45));

        assertEquals(Duration.ofMinutes(45), patient.stall());
        assertEquals(Duration.ofHours(3), patient.ceiling(), "and the other is untouched");
        assertEquals(Duration.ofMinutes(20), Watch.shipped().stall(), "the default is unchanged");
    }

    @Test
    void aWatchThatCouldOnlyEverFireIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Watch(Duration.ZERO, Duration.ofHours(3)),
                "a stall bound of zero ends every call, including the ones that are working");
        assertThrows(IllegalArgumentException.class,
                () -> new Watch(Duration.ofHours(4), Duration.ofHours(3)),
                "a ceiling below the stall makes the ceiling the only guard that ever fires");
    }

    @Test
    void aTraceThatThrowsDoesNotSwallowTheFailureUnderneath() {
        // Recording must not break the call it is recording — but it must not hide it either.
        Trace broken = new Notes() {
            @Override
            public void thought(String f, String t, String c) {
                throw new IllegalStateException("the record is unwritable");
            }
        };
        ChatModel guarded = Streamed.over(saysThenStalls("half a thought"), broken,
                new Watch(Duration.ofMillis(1), Duration.ofMinutes(5)));

        IllegalStateException stalled = assertThrows(IllegalStateException.class,
                () -> guarded.chat(ask()));
        assertTrue(stalled.getMessage().contains("not producing"),
                "the stall is the news, not the unwritable record: " + stalled.getMessage());
        assertFalse(stalled.getMessage().contains("unwritable"), stalled.getMessage());
    }

    // ---------------------------------------------------------------- the fakes

    private static ChatRequest ask() {
        return ChatRequest.builder().messages(UserMessage.from("who speaks first?")).build();
    }

    /** Emits the given pieces, then never completes. */
    private static StreamingChatModel saysThenStalls(String... pieces) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                for (String piece : pieces) {
                    handler.onPartialResponse(piece);
                }
                // and nothing more, ever
            }
        };
    }

    private static StreamingChatModel saysThenEnds(FinishReason why, String content, String... pieces) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                for (String piece : pieces) {
                    handler.onPartialResponse(piece);
                }
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(content))
                        .metadata(ChatResponseMetadata.builder().finishReason(why).build())
                        .build());
            }
        };
    }

    private static class Notes implements Trace {
        final List<String> thoughts = new ArrayList<>();

        public void thought(String f, String t, String c) {
            thoughts.add(f + " :: " + t);
        }

        public void asked(String a, String p, String r) { }
        public void progress(String k, String n) { }
        public void applied(String s, String w) { }
        public void tool(String a, String t, String g, String r) { }
        public void built(String p, Trace.Outcome r) { }
        public void settled(String k, String s, String w, boolean b, boolean a) { }
        public void failed(String k, Throwable c) { }
        public void priced(String k, String m, String i) { }
    }
}
