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
 * <p>So this is the seam and not the policy. The caller is handed {@link Turns} before each request
 * and may shadow ranges of it. Doing nothing is the default and is exactly today's behaviour.
 *
 * <p>THE VIEW IS SHORTENED, THE CONVERSATION IS NOT. A replacement is an APPEND that cites the
 * positions it covered, so the shadowed turns stay in the log and a reader can see what the agent
 * stopped being able to see, and when. That is the same split the record already makes between what
 * a model is shown and what the corpus keeps — {@link Turns#messages()} against
 * {@link Turns#spoken()}.
 *
 * <p>THE SHAPE IS TAKEN FROM {@code @deepseek-ai/dsh-session} RATHER THAN INVENTED. There the
 * append-only log is the source of truth and the message history is DERIVED from it, and a
 * replacement carries {@code surfaceOp: replace(start, end)} with the sequence numbers it shadowed.
 * This is that idea at the size a library can carry: no session, no persistence, no sequence numbers
 * that outlive a process.
 */
@FunctionalInterface
public interface Between {

    /**
     * Called before each request. Do nothing, or call {@link Turns#replace} as often as you like;
     * what gets sent is {@link Turns#messages()} afterwards.
     *
     * <p>A RANGE RATHER THAN A NEW LIST, and that changed in 0.21.0 from a shape shipped in 0.20.0.
     * Returning a list said what to SEND and lost what it replaced: ratchet kept the original, so
     * nothing was destroyed, but nobody could tell afterwards which turns a compaction had shadowed
     * or build the next one on the last. A range replacement is an append that cites what it
     * covered, which is dsh's shape and the reason a compacted conversation stays legible.
     *
     * @param turns everything said so far, and the means to shadow a range of it
     */
    void turn(Turns turns);

    /** Send the whole conversation, which is what every caller got before this seam existed. */
    static Between whole() {
        return turns -> {
        };
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
