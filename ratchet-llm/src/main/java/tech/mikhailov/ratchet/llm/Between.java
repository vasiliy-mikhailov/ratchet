package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WHAT THE NEXT REQUEST CARRIES, DECIDED BY THE CALLER BETWEEN TURNS.
 *
 * <p>{@link Asking} appends and never removes, so a long agent walks its conversation into the
 * context window and every turn after that is cut off mid tool call. The answer to that is
 * compaction, and compaction is not this library's to do: deciding WHEN needs the route's real
 * context capacity, deciding WHAT needs to know which results are cheap to fetch again, and deciding
 * WHETHER needs to know if anyone is watching. A library called from inside somebody else's program
 * knows none of those.
 *
 * <p>So this is the seam and not the policy. The caller is handed the whole conversation before each
 * request and returns what should be sent. Returning it unchanged is the default and is exactly
 * today's behaviour.
 *
 * <p>THE VIEW IS SHORTENED, THE CONVERSATION IS NOT. What comes back here is used for one request
 * and not stored: {@link Asking} keeps appending to its own list, so the next call sees everything
 * that has happened and can decide again. Nothing a caller drops is lost, which is the same split
 * the record already makes between what a model is shown and what the corpus keeps.
 *
 * <p>THE SHAPE IS TAKEN FROM {@code @deepseek-ai/dsh-session} RATHER THAN INVENTED. There, the
 * append-only log is the source of truth and the message history is DERIVED from it; a replacement
 * is itself an append carrying {@code surfaceOp: replace(start, end)} and the sequence numbers it
 * shadowed, and the shadowed events stay in the log. This is that idea at the size a library can
 * carry: no session, no sequence numbers, no fold — one function, called between turns.
 */
@FunctionalInterface
public interface Between {

    /**
     * @param conversation everything said so far, oldest first, including this library's own
     *                     system turn — never modified by the caller, who returns a new list
     * @return the messages the next request should carry; the same list is a valid answer
     */
    List<Said> turn(List<Said> conversation);

    /** Send the whole conversation, which is what every caller got before this seam existed. */
    static Between whole() {
        return conversation -> conversation;
    }

    /**
     * WHETHER A CUT BEFORE {@code at} LEAVES NO TOOL CALL UNANSWERED ACROSS IT.
     *
     * <p>An assistant turn asks for tools and the results answer it; a cut between them orphans the
     * call. That is not a tidiness problem — an orphaned call is the shape that poisons a
     * conversation for every later turn, and one that reached a server drove its tool-call parser
     * into a loop that took every endpoint on that engine down for three hours. A compactor that
     * manufactures one is worse than no compactor.
     *
     * <p>Contract taken from dsh's {@code toolPairingBalancedBefore}: true when no unanswered call
     * crosses the cut, and a THROW rather than a false when a result answers a call that was never
     * made. That is corrupt input rather than an unbalanced edge, and conflating the two is how the
     * guard against orphaned calls comes to make one.
     *
     * @throws IllegalArgumentException when a tool result has no preceding open call
     */
    static boolean balancedBefore(List<Said> conversation, int at) {
        return open(conversation, Math.max(0, Math.min(at, conversation.size()))).isEmpty();
    }

    /** The same for the cut immediately after {@code at}, which is the far edge of a range. */
    static boolean balancedAfter(List<Said> conversation, int at) {
        return balancedBefore(conversation, at + 1);
    }

    /**
     * The nearest index at or before {@code at} where a cut is balanced, or 0.
     *
     * <p>Offered because every caller of the two above wants this next, and writing it wrongly is
     * how a range ends up one message inside a tool pair.
     */
    static int balancedAtOrBefore(List<Said> conversation, int at) {
        for (int i = Math.max(0, Math.min(at, conversation.size())); i > 0; i--) {
            if (balancedBefore(conversation, i)) {
                return i;
            }
        }
        return 0;
    }

    /** Call ids asked for and not yet answered in {@code conversation[0, upTo)}. */
    private static Set<String> open(List<Said> conversation, int upTo) {
        Set<String> waiting = new HashSet<>();
        List<String> asked = new ArrayList<>();
        for (int i = 0; i < upTo; i++) {
            Said said = conversation.get(i);
            if (said.role() == Said.Role.ASSISTANT) {
                said.calls().forEach(call -> {
                    waiting.add(call.id());
                    asked.add(call.id());
                });
            } else if (said.role() == Said.Role.TOOL) {
                String answers = said.answering() == null ? "" : said.answering().id();
                if (!waiting.remove(answers)) {
                    throw new IllegalArgumentException("a tool result at " + i + " answers "
                            + (answers.isEmpty() ? "no call at all" : "call " + answers)
                            + ", which was never asked for. The calls open here are " + asked
                            + ". That is a conversation that could not have been produced by this "
                            + "loop, and cutting it anywhere would be guesswork.");
                }
            }
        }
        return waiting;
    }
}
