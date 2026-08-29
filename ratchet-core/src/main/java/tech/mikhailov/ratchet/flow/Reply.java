package tech.mikhailov.ratchet.flow;


/**
 * THE WORD A REPLY SETTLES ON, WHICH IS THE ONLY CONTROL FLOW A MODEL HAS HERE.
 *
 * <p>Every verifier in this design answers with one of a closed set, and this is what reads it.
 * It lived on the class where the first caller happened to be, which meant {@link Flow#triad} and
 * everything else that needed a verdict reached into a program class to parse a sentence. A parser
 * is not a program, and eight call sites across three classes is not a private helper.
 *
 * <p>The rules below exist because each was got wrong first: the approving word was both the
 * earliest-colliding and the default, so it was the easiest verdict to trigger by accident, which
 * is exactly backwards for a construction whose whole point is that a reviewer can stop the work.
 */
public final class Reply {

    private Reply() {
    }

    /**
     * WHICH VERDICT AN AGENT ACTUALLY GAVE, which is not the first place its letters appear.
     *
     * <p>This was {@code indexOf} over the whole lowercased reply, taking the earliest hit. Three
     * collisions follow from that and all three are ordinary English rather than adversarial input:
     *
     * <ul>
     *   <li>{@code done} is inside "not done", "nothing done", "abandoned". A verifier that opens by
     *       denying completion scored {@code done} before reaching its real verdict.
     *   <li>{@code again} is inside "against".
     *   <li>{@code sound} is inside "unsound", so the security critic's rejection read as approval.
     * </ul>
     *
     * <p>The first is the expensive one. {@code done} is both the earliest-colliding word and the
     * default when nothing matches, so the approving answer was the easiest to trigger by accident,
     * which is precisely backwards for a construction whose whole purpose is that a reviewer can
     * stop the work.
     *
     * <p>Three rules, in order. A line that STARTS with one of the words is the verdict, because
     * that is what every prompt asks for. Failing that, a whole-word match wins, which kills
     * "unsound" and "against" outright. And a match immediately preceded by a negation is not a
     * match, which kills "not done".
     */
    /**
     * THE VERDICT IS THE FIRST WORD OF A LINE, and everything else is not a verdict.
     *
     * <p>WHAT THIS USED TO BE, AND WHY IT IS NOT ANY MORE. Two passes: seven punctuation variants
     * per allowed word on the first, then a scan of the WHOLE reply as prose — word-boundary
     * regexes so {@code sound} could not hide inside {@code unsound}, a seventeen-form English
     * negation detector over a 24-character lookbehind so {@code "this is not done"} did not read
     * as done, and earliest-match arbitration for a reply that named two verdicts. Sixteen of this
     * file's forty-six lines.
     *
     * <p>All of it defended one case: A VERIFIER THAT ANSWERS IN PROSE. Every verifier in this
     * library and in the pipeline built on it is plain code returning a literal — {@code "done"} or
     * {@code "again: <reason>"}, 11 and 24 of them — so that case is not rare here, it has never
     * occurred. The collisions the word-boundary regex prevented, and the two verdicts the
     * earliest-match rule arbitrated between, could only arise BECAUSE the reply was being searched
     * as prose. They were requirements of the implementation rather than of the system.
     *
     * <p>The first token of the line, with punctuation trimmed, covers every form the enumeration
     * covered — {@code done}, {@code done:}, {@code done!}, {@code again, because…} — in one rule
     * instead of seven, and covers punctuation nobody thought of.
     *
     * @return the verdict, or "" when the reply names none. NOT the first allowed word: this used
     *         to fall back to {@code allowed[0]}, which every caller passes as the approving word,
     *         so silence approved — and {@link Flow.Triad#verdictOf} carried a second blank check
     *         to undo it. Two layers doing one job, with the lower one defaulting to the reading
     *         this project argues against everywhere else. What silence means is now decided once,
     *         by the caller that knows.
     */
    public static String word(String reply, String... allowed) {
        if (reply == null) {
            return "";
        }
        for (String line : reply.lines().toList()) {
            String first = line.strip().toLowerCase()
                    .replaceFirst("^[-*>#`\\s]+", "")
                    .split("[\\s\\p{Punct}]", 2)[0];
            for (String w : allowed) {
                if (first.equals(w)) {
                    return w;
                }
            }
        }
        return "";
    }
}
