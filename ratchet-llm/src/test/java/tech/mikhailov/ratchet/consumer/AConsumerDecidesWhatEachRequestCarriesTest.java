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
    void aConsumerDecidesWhatEachRequestCarries() {
        List<Integer> sizesSent = new ArrayList<>();
        // It counts its own turns rather than the request size, because the request size is the
        // thing under test: a model that stops when the REQUEST grows never stops against a caller
        // whose whole purpose is to stop the request growing.
        Chat model = ask -> {
            sizesSent.add(ask.messages().size());
            return sizesSent.size() > 3 ? said("done") : wanting("ping");
        };

        // Their policy: never send more than the system turn and the last two things said.
        Between theirs = conversation -> conversation.size() <= 3 ? conversation
                : List.of(conversation.get(0), conversation.get(conversation.size() - 2),
                        conversation.get(conversation.size() - 1));

        String answer = new Asking(model, "you are a test", one("ping"), "agent:test", null,
                Telling.upTo(8_000), Budget.shipped(), theirs).run("go");

        assertEquals("done", answer, "the loop still finishes");
        assertTrue(sizesSent.stream().skip(2).allMatch(n -> n <= 3),
                "and every request after the conversation grew carried what they chose, not what "
                        + "this library accumulated: " + sizesSent);
    }

    /** The view is shortened. The conversation is not, so the next turn can decide again. */
    @Test
    void theConversationItselfIsNeverShortened() {
        List<Integer> seenByThem = new ArrayList<>();
        Chat model = ask -> seenByThem.size() < 3 ? wanting("ping") : said("done");

        Between watching = conversation -> {
            seenByThem.add(conversation.size());
            return List.of(conversation.get(0));
        };

        new Asking(model, "you are a test", one("ping"), "agent:test", null,
                Telling.upTo(8_000), Budget.shipped(), watching).run("go");

        assertTrue(seenByThem.get(seenByThem.size() - 1) > seenByThem.get(0),
                "they were handed a growing conversation even though every request they returned "
                        + "carried one message: " + seenByThem);
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

    /** Compaction shortens what is sent. It does not remove the question. */
    @Test
    void aConsumerThatHandsBackNothingIsRefused() {
        Chat model = ask -> said("done");

        assertThrows(IllegalStateException.class,
                () -> new Asking(model, "you are a test", one("ping"), "agent:test", null,
                        Telling.upTo(8_000), Budget.shipped(), conversation -> List.of()).run("go"));
        assertThrows(IllegalStateException.class,
                () -> new Asking(model, "you are a test", one("ping"), "agent:test", null,
                        Telling.upTo(8_000), Budget.shipped(), conversation -> null).run("go"));
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
