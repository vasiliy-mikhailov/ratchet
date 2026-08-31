package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHO IS SPEAKING ARRIVES WITH THE QUESTION, RATHER THAN BEING RECONSTRUCTED FROM ITS CONTENT.
 *
 * <p>ratchet#10. Until 0.14.0 this was answered by scanning a process-global map for the longest
 * registered system prompt contained in the message. That was forced while the request type belonged
 * to a client library and had nowhere to put a label — and 0.13.0 removed the reason without
 * removing the registry, because this library defines {@link Ask} now and nothing prevented a third
 * component.
 *
 * <p>WHAT IT COST, measured by the consumer who reported it: 277,022 exchange rows in one sweep,
 * each scanning around twenty registered keys, needle and haystack both tens of kilobytes, because
 * one agent's key was its prompt with a 23 KB bill of materials spliced into it. All to recover a
 * string {@link Asking} was holding in a field and already handing to the tool listener exactly.
 *
 * <p>Two mechanisms answered one question and only one of them could be wrong. The tests below pin
 * the two failures that were structural rather than accidental: a consumer forgetting to register,
 * which degraded the record in silence, and two agents sharing a prompt, which the old key could not
 * tell apart even in principle.
 */
class TheAgentNameTravelsWithTheQuestionTest {

    private static final String PROMPT = "you are a test";

    @Test
    void askingPutsItsOwnLabelOnEveryQuestion() {
        // THE ANSWER WAS THREE LINES AWAY. Asking holds the label and hands it to the tool listener
        // verbatim; the exchange rows were reconstructing it by substring search in the same class.
        List<Ask> asked = new ArrayList<>();
        Chat model = ask -> {
            asked.add(ask);
            return new Reply("done", "", List.of(), Ending.STOPPED, Spend.NONE);
        };

        new Asking(model, PROMPT, Map.of(), "agent:survey-doer", null).run("go");

        assertEquals("agent:survey-doer", asked.get(0).from());
    }

    @Test
    void theExchangeRowCarriesItWithNothingRegisteredAnywhere() {
        // The failure the javadoc of the old design conceded: "Call this once per agent, or every
        // exchange is attributed to nobody: 737 of them in one sweep were." There is nothing to
        // call now, so there is nothing to forget.
        List<Trace.Exchange> rows = new ArrayList<>();
        Listening listening = new Listening(recording(rows));
        Ask ask = new Ask(List.of(Said.system(PROMPT), Said.user("the question")), List.of(),
                "agent:pins");

        listening.sending(ask);
        listening.back(ask, new Reply("ok", "", List.of(), Ending.STOPPED, Spend.NONE), 12);

        assertEquals("agent:pins", rows.get(0).agent(), "the request row");
        assertEquals("agent:pins", rows.get(1).agent(), "and the reply row");
    }

    @Test
    void twoAgentsSharingAPromptAreStillToldApart() {
        // THE COLLISION THE OLD KEY COULD NOT RESOLVE EVEN IN PRINCIPLE, because the key WAS the
        // prompt: two agents with byte-identical prompts resolved to whichever registered last.
        // Not live in the reporting consumer's tree -- 75 files, 75 distinct contents -- but
        // platform variants of one stage are exactly where it would have appeared.
        List<Trace.Exchange> rows = new ArrayList<>();
        Listening listening = new Listening(recording(rows));

        listening.sending(new Ask(List.of(Said.system(PROMPT), Said.user("a")), List.of(),
                "agent:gradle"));
        listening.sending(new Ask(List.of(Said.system(PROMPT), Said.user("b")), List.of(),
                "agent:maven"));

        assertEquals("agent:gradle", rows.get(0).agent());
        assertEquals("agent:maven", rows.get(1).agent(),
                "identical prompts, and the two are still distinguishable");
    }

    @Test
    void thePromptIsStillWrittenOncePerAgentAndNamedAfterThat() {
        // Unchanged behaviour, asserted because the de-duplication used to be keyed off the
        // reconstructed name and is now keyed off the carried one. It is identical on every call of
        // an agent and runs to thousands of characters, so writing it each time would put the same
        // paragraphs on disk a hundred times per run.
        List<Trace.Exchange> rows = new ArrayList<>();
        Listening listening = new Listening(recording(rows));
        Ask ask = new Ask(List.of(Said.system(PROMPT), Said.user("q")), List.of(), "agent:pins");

        listening.sending(ask);
        listening.sending(ask);

        assertTrue(rows.get(0).sent().contains(PROMPT), rows.get(0).sent());
        assertTrue(rows.get(1).sent().contains("agent:pins's prompt, unchanged"),
                rows.get(1).sent());
        assertTrue(!rows.get(1).sent().contains(PROMPT),
                "the second one names it rather than repeating it: " + rows.get(1).sent());
    }

    @Test
    void anUnlabelledQuestionIsAttributedToNobodyRatherThanToAGuess() {
        // A consumer building an Ask by hand and not saying who it is gets an empty column, which is
        // honest. The old design's answer here was a substring search that could return the WRONG
        // agent -- a column that is silently incorrect is worse than one that is visibly blank.
        List<Trace.Exchange> rows = new ArrayList<>();
        new Listening(recording(rows)).sending(Ask.of(List.of(Said.user("who am I"))));

        assertEquals("", rows.get(0).agent());
        assertTrue(rows.get(0).sent().contains("who am I"), rows.get(0).sent());
    }

    private static Trace recording(List<Trace.Exchange> into) {
        return new Trace() {
            @Override
            public void exchanged(Exchange exchange) {
                into.add(exchange);
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
            public void thought(String agent, String finishReason, String thinking, String content) {
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
            public void progress(String key, String note) {
            }

            @Override
            public void priced(String key, String minutes, String itemisation) {
            }
        };
    }
}
