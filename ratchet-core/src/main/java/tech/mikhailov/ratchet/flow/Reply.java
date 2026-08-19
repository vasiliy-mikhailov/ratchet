package tech.mikhailov.ratchet.flow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE WORD A REPLY SETTLES ON, WHICH IS THE ONLY CONTROL FLOW A MODEL HAS HERE.
 *
 * <p>Every verifier in this design answers with one of a closed set, and this is what reads it.
 * It lived on the class where the first caller happened to be, which meant {@link Triad} and
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
    public static String word(String reply, String... allowed) {
        if (reply == null || reply.isBlank()) {
            return allowed[0];
        }
        for (String line : reply.lines().toList()) {
            String l = line.strip().toLowerCase().replaceFirst("^[-*>#`\\s]+", "");
            for (String w : allowed) {
                if (l.equals(w) || l.startsWith(w + ":") || l.startsWith(w + " ")
                        || l.startsWith(w + ".") || l.startsWith(w + ",")
                        || l.startsWith(w + ";") || l.startsWith(w + "!")) {
                    return w;
                }
            }
        }
        String lower = reply.toLowerCase();
        int best = Integer.MAX_VALUE;
        String chosen = allowed[0];
        for (String w : allowed) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(lower);
            while (m.find()) {
                if (negated(lower, m.start())) {
                    continue;
                }
                if (m.start() < best) {
                    best = m.start();
                    chosen = w;
                }
                break;
            }
        }
        return chosen;
    }

    /** Whether the words just before a match turn it into its opposite. */
    private static boolean negated(String text, int at) {
        String before = text.substring(Math.max(0, at - 24), at);
        return NEGATION.matcher(before).find();
    }

    private static final Pattern NEGATION = Pattern.compile(
            "\\b(not|isn't|isnt|is not|no|never|nothing|cannot|can't|cant|wasn't|wasnt|"
                    + "aren't|arent|hasn't|hasnt|far from|less than)\\b[\\s\\p{Punct}]*$");
}
