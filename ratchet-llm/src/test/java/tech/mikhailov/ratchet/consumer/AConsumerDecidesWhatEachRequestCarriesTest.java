package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tech.mikhailov.ratchet.llm.Ask;
import tech.mikhailov.ratchet.llm.Asking;
import tech.mikhailov.ratchet.llm.Between;
import tech.mikhailov.ratchet.llm.Budget;
import tech.mikhailov.ratchet.llm.Called;
import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Chat;
import tech.mikhailov.ratchet.llm.Ending;
import tech.mikhailov.ratchet.llm.Reply;
import tech.mikhailov.ratchet.llm.Said;
import tech.mikhailov.ratchet.llm.Spend;
import tech.mikhailov.ratchet.llm.Tool;
import tech.mikhailov.ratchet.llm.Turns;
import tech.mikhailov.ratchet.record.Telling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COMPACTION IS THE CALLER'S, AND THIS IS THE SEAM IT SITS ON.
 *
 * <p>In another package because that is the only place it means anything: a harness knows the
 * route's context window, which results are cheap to fetch again, and whether anyone is watching.
 * This library knows none of those, so it does not decide — it hands over the conversation and asks
 * what the next request should carry.
 */
class AConsumerDecidesWhatEachRequestCarriesTest {

    @Test
    void aConsumerShadowsARangeAndThatIsWhatGetsSent() {
        List<Integer> sizesSent = new ArrayList<>();
        Chat model = ask -> {
            sizesSent.add(ask.messages().size());
            return sizesSent.size() > 3 ? said("done") : wanting("ping");
        };

        // Their policy: once the conversation is long, shadow the middle with one line, cutting at
        // an edge where no tool call is separated from its result.
        Between theirs = turns -> {
            if (turns.size() > 4) {
                int to = Between.balancedAtOrBefore(turns.messages(), turns.size() - 2);
                if (to > 2) {
                    turns.replace(2, to, Said.user("[earlier turns summarised]"));
                }
            }
        };

        String answer = new Asking(model, "you are a test", one("ping"), "agent:test", null,
                Telling.upTo(8_000), Budget.shipped(), theirs).run("go");

        assertEquals("done", answer, "the loop still finishes");
        assertTrue(sizesSent.get(sizesSent.size() - 1) <= 5,
                "and the last request carried what they left standing, not everything said: "
                        + sizesSent);
    }

    /**
     * TWO AUDIENCES, ONE LOG. The model sees the surface, which shadows what was replaced. A reader
     * sees everything said, because a landed replacement would otherwise erase conversation they
     * have already seen — dsh warns about exactly this and names the distinction on its own log.
     */
    @Test
    void theTranscriptKeepsWhatTheModelStoppedSeeing() {
        Turns turns = new Turns();
        turns.said(Said.system("you are a test"));
        turns.said(Said.user("go"));
        turns.said(Said.user("something the model will stop seeing"));
        turns.said(Said.user("and so will this"));

        turns.replace(1, 3, Said.user("[three turns summarised]"));

        assertEquals(3, turns.messages().size(), "the model sees the summary and what followed");
        assertEquals("[three turns summarised]", turns.messages().get(1).text());
        assertEquals(5, turns.spoken().size(),
                "and the transcript has all four originals plus the replacement, because nothing "
                        + "was destroyed — a second view was shortened");
        assertEquals(1, turns.generation(), "one replacement has landed");
    }

    /** A replacement is an append, and it says which positions it covered. */
    @Test
    void aReplacementIsAnAppendThatSaysWhatItShadowed() {
        Turns turns = new Turns();
        turns.said(Said.system("s"));
        turns.said(Said.user("a"));
        turns.said(Said.user("b"));

        turns.replace(1, 3, Said.user("summary"));

        List<Turns.Entry> log = turns.spoken();
        assertTrue(log.get(0).appended() && log.get(1).appended() && log.get(2).appended(),
                "the first three went on the tail");
        assertFalse(log.get(3).appended(), "and the fourth went over something");
        assertEquals(List.of(1, 2), log.get(3).shadowed(),
                "citing the positions it covered, which is what makes a compaction legible later");
    }

    @Test
    void sendingTheWholeConversationIsWhatEveryCallerHadBefore() {
        List<Integer> sizes = new ArrayList<>();
        Chat model = ask -> {
            sizes.add(ask.messages().size());
            return sizes.size() < 3 ? wanting("ping") : said("done");
        };

        new Asking(model, "you are a test", one("ping"), "agent:test", null,
                Telling.upTo(8_000), Budget.shipped(), Between.whole()).run("go");

        assertEquals(List.of(2, 4, 6), sizes, "the default changes nothing");
    }

    /**
     * AN ORPHANED CALL IS THE SHAPE THAT POISONS A CONVERSATION for every later turn, and one that
     * reached a server drove its tool-call parser into a loop that took every endpoint on that
     * engine down for three hours. A compactor that manufactures one is worse than no compactor.
     */
    @Test
    void aCutBetweenACallAndItsResultIsNotBalanced() {
        Called call = new Called("c1", "ping", "{}");
        List<Said> conversation = List.of(
                Said.system("you are a test"), Said.user("go"),
                Said.assistant("", List.of(call)), Said.result(call, "pong"));

        assertTrue(Between.balancedBefore(conversation, 2), "before the assistant asked, nothing "
                + "is open");
        assertFalse(Between.balancedBefore(conversation, 3), "between the call and its result, one "
                + "call is open and a cut there orphans it");
        assertTrue(Between.balancedBefore(conversation, 4), "and after the result it is answered");
        assertTrue(Between.balancedAfter(conversation, 3), "the far edge of the same pair");
    }

    /** Corrupt input, not an unbalanced edge — and conflating them is how a guard makes the thing it guards against. */
    @Test
    void aResultAnsweringACallThatWasNeverMadeIsRefusedRatherThanCalledUnbalanced() {
        List<Said> impossible = List.of(
                Said.system("s"), Said.result(new Called("ghost", "ping", "{}"), "pong"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Between.balancedBefore(impossible, 2)).getMessage().contains("never asked for"),
                "a false here would invite a caller to go looking for a balanced edge in a "
                        + "conversation that has none, rather than to stop");
    }

    @Test
    void theNearestBalancedCutIsFoundOrTheStart() {
        Called call = new Called("c1", "ping", "{}");
        List<Said> conversation = List.of(
                Said.system("s"), Said.user("go"),
                Said.assistant("", List.of(call)), Said.result(call, "pong"));

        assertEquals(2, Between.balancedAtOrBefore(conversation, 3),
                "asked for a cut inside the pair, it walks back to the edge before it");
        assertEquals(4, Between.balancedAtOrBefore(conversation, 4), "and leaves a good one alone");
    }

    /**
     * THE EDGE CHECK IS NOT THE CALLER'S TO SKIP. An orphaned call poisons a conversation for every
     * later turn, and one that reached a server wedged its tool-call parser for three hours. So a
     * range that would make one is refused rather than trusted.
     */
    @Test
    void aRangeThatWouldOrphanACallIsRefused() {
        Called call = new Called("c1", "ping", "{}");
        Turns turns = new Turns();
        turns.said(Said.system("s"));
        turns.said(Said.user("go"));
        turns.said(Said.assistant("", List.of(call)));
        turns.said(Said.result(call, "pong"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> turns.replace(1, 3, Said.user("x")))
                .getMessage().contains("cuts a tool call away from its result"));
        assertEquals(4, turns.messages().size(), "and nothing moved");
    }

    /** An empty conversation is now impossible rather than caught: a range puts something back. */
    @Test
    void aRangeThatIsNotARangeIsRefused() {
        Turns turns = new Turns();
        turns.said(Said.system("s"));
        turns.said(Said.user("go"));

        assertThrows(IllegalArgumentException.class, () -> turns.replace(1, 1, Said.user("x")));
        assertThrows(IllegalArgumentException.class, () -> turns.replace(0, 9, Said.user("x")));
        assertThrows(IllegalArgumentException.class, () -> turns.replace(-1, 1, Said.user("x")));
    }

    private static Reply wanting(String name) {
        return new Reply("", "", List.of(new Called("c", name, "{}")), Ending.TOOLS, Spend.NONE);
    }

    private static Reply said(String text) {
        return new Reply(text, "", List.of(), Ending.STOPPED, Spend.NONE);
    }

    private static Map<Tool, Calling> one(String name) {
        return Map.of(new Tool(name, "a tool", "{}"), call -> "pong");
    }
}
