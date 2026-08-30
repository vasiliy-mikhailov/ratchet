package tech.mikhailov.ratchet.record;

/**
 * WHAT WE KEPT, AND WHAT WE OMITTED. One question, one implementation.
 *
 * <p>Six places in this library cut text for output and each brought its own marker. Three of them
 * write the SAME sentence — {@code ... (truncated, total N chars)} — differing only in leading
 * whitespace. {@code Recording} writes a fourth and is the only one that says what to do about it.
 * {@code Refused} writes a bare ellipsis carrying no magnitude at all. And {@code Reasoning} cuts
 * the offending line at ninety characters with no marker whatever, in the diagnostic for a runaway,
 * where the repeated line is the evidence.
 *
 * <p>THE NOTICE IS PAID FOR OUT OF THE BUDGET, which is the property the hand-written versions
 * lacked. A marker costs about thirty characters, so a line overrunning its bound by less came back
 * LARGER for having been cut — measured on a real record, a 191-character line at a bound of 180
 * rendered as 213, and lost its end for the privilege. 0.18.1 fixed that by declining to cut. This
 * is the structural form: the notice's cost is reserved before the cut is made, so a result is
 * always at most the budget, always strictly smaller than what it replaced, and a second pass over
 * it changes nothing.
 *
 * <p>CUTS LAND ON CODE POINT BOUNDARIES. {@link String#substring} counts UTF-16 units, so a cut
 * through an emoji leaves a lone surrogate. On the wire that becomes {@code ?}; in the record it is
 * worse, because {@code Files.writeString} refuses to encode it and {@link JsonlTrace} catches the
 * failure and drops the whole row — under a comment saying a silently absent trace is worse than a
 * loud one.
 *
 * <p>The budget is in characters rather than bytes because every caller here bounds text a model or
 * a reader will see, and a character is what those callers already promise. A byte budget belongs
 * where the thing being bounded is a byte stream.
 *
 * @param text    what to write: the whole input, or a cut of it with the notice already appended
 * @param omitted how much did not survive, as a fact about the BUDGET and never about the input
 */
public record Retained(String text, Omitted omitted) {

    /**
     * HOW MUCH WAS LEFT OUT, AND {@link Unknown} IS A REAL ANSWER.
     *
     * <p>A record that cannot say how much it withheld must say THAT, rather than say nothing and
     * read as complete. One consumer measured a column whose p90, p99 and maximum were all the same
     * number, because the bound had flattened the distribution that would have shown it was wrong.
     *
     * <p>OMISSION IS A BUDGET FACT AND NEVER "INCOMPLETE". A permission failure, an unreadable file,
     * a provider that returned half an answer — those belong to whoever knows what they mean, in
     * their own field. Folding them in here is the mistake this naming most invites.
     */
    public sealed interface Omitted {

        /** Everything fitted. */
        record None() implements Omitted {
        }

        /** Exactly this many characters of the input are not in {@link #text}. */
        record Exact(int chars) implements Omitted {
        }

        /** Something was left out and this cannot say how much. Not the same as {@link None}. */
        record Unknown() implements Omitted {
        }
    }

    /** The sentence three call sites were writing separately, with the total they all carried. */
    private static String notice(int total) {
        return " ... (truncated, total " + total + " chars)";
    }

    /**
     * The whole text, or its opening cut to fit {@code budget} INCLUDING the notice.
     *
     * @param budget the size of the entire result, notice included; must be positive
     */
    public static Retained head(String text, int budget) {
        if (budget < 1) {
            throw new IllegalArgumentException("a budget of " + budget + " keeps nothing, which is "
                    + "not a smaller result but no result at all");
        }
        String whole = text == null ? "" : text;
        int total = whole.codePointCount(0, whole.length());
        if (total <= budget) {
            return new Retained(whole, new Omitted.None());
        }
        String marker = notice(total);
        int room = budget - marker.codePointCount(0, marker.length());
        if (room < 1) {
            // THE REPLACEMENT WOULD BE NO SMALLER THAN WHAT IT REPLACES, so there is nothing to
            // gain by making it: the reader would lose the end of the text and pay for the loss.
            // dsh states the same rule as a load-time invariant on its config; this states it here
            // because ratchet's budgets arrive from a consumer's policy at call time.
            return new Retained(whole, new Omitted.None());
        }
        return new Retained(cut(whole, room) + marker, new Omitted.Exact(total - room));
    }

    /** The first {@code points} code points, never splitting one. */
    private static String cut(String s, int points) {
        return s.substring(0, s.offsetByCodePoints(0, points));
    }

    /** This, with the caller's own guidance on how to get the rest. */
    public Retained recoverableBy(String recovery) {
        if (omitted instanceof Omitted.None || recovery == null || recovery.isBlank()) {
            return this;
        }
        return new Retained(text + " " + recovery, omitted);
    }

    /** Whether anything was left out, which is the question most callers actually ask. */
    public boolean cut() {
        return !(omitted instanceof Omitted.None);
    }
}
