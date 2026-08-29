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
     * <p>ONE RULE. A line that OPENS with one of the caller's words, and ends it there, is the
     * verdict. That is what every prompt asks for, and it kills "unsound", "against" and "not done"
     * structurally rather than by three further rules that each had to be got right.
     *
     * <p>NOTHING MATCHED IS THE EMPTY STRING, AND THIS CHANGED IN 0.15.0. Until 0.14.0 a silent or
     * verdictless reply returned {@code allowed[0]}, and the only caller passes the APPROVING word
     * first, so a verifier that wrote a paragraph without a verdict approved the work. It answers
     * nothing now and the caller decides what nothing means — {@link Flow#triad} reads it as
     * {@code again}. A consumer calling this directly must handle the empty string; there is
     * deliberately no default, because a default here is a vote nobody cast.
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
            String opening = line.strip().toLowerCase().replaceFirst("^[-*>#`\\s]+", "");
            for (String w : allowed) {
                if (opening.startsWith(w) && ended(opening, w.length())) {
                    return w;
                }
            }
        }
        return "";
    }

    /**
     * THE ALLOWED WORD ENDED HERE, rather than being the opening of a longer one.
     *
     * <p>This used to split the LINE on {@code [\s\p{Punct}]} and compare the first token, which
     * reads as the simpler rule and is the same rule only while every allowed word is one word.
     * {@code \p{Punct}} contains the hyphen, so a vocabulary of {@code blocked-dependency},
     * {@code behavior-change} and {@code off-target} tokenised to {@code blocked}, {@code behavior}
     * and {@code off}, none of which can equal what the caller allowed. Every such verdict came
     * back as the empty string, silently, into the one field a corpus is aggregated on.
     *
     * <p>IT PASSED EVERYTHING BECAUSE OF WHAT THIS LIBRARY ITSELF ASKS FOR. The only caller in
     * ratchet passes {@code done}, {@code again} and {@code replan} — three single words — so no
     * test here could see it, and it was reported by the consumer whose vocabulary is hyphenated.
     * The bound is now measured from the END of the caller's word, so the vocabulary decides where
     * the token ends instead of a character class guessing.
     *
     * <p>A hyphen and an underscore count as continuing the word, which is what stops
     * {@code blocked} matching a line that says {@code blocked-dependency}: a shorter verdict must
     * not swallow a longer one that happens to begin with it.
     */
    private static boolean ended(String opening, int at) {
        if (at == opening.length()) {
            return true;
        }
        char next = opening.charAt(at);
        return !Character.isLetterOrDigit(next) && next != '-' && next != '_';
    }
}
