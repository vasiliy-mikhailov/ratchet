package tech.mikhailov.ratchet.llm;

/**
 * WHAT ONE AGENT MAY SPEND BEFORE SOMEBODY IS TOLD IT IS NOT GOING TO FINISH.
 *
 * <p>This replaces a count of tool-call rounds, and the unit is the change. The old bound was 25,
 * inherited from the jar this library replaced rather than chosen, and it counted ROUNDS: one turn
 * asking for five tools cost one, and the busiest recorded conversation fitted 465 calls inside it.
 * So it bounded neither the work nor the cost — twenty-five turns of an edit tool returning two
 * hundred characters and twenty-five turns of a grep returning megabytes were the same number to it.
 * It fired 60,173 times in one corpus, on 59 of 208 runs, each firing costing a second whole
 * conversation through the re-ask that followed. The same task family completes in a couple of hours
 * against a loop with no round bound at all, on this same model.
 *
 * <p>MEASURED AGAINST ONE CONSUMER'S REAL WORK, which is the number to quote rather than any of the
 * above. Across 75 completed agent turns: median 14 rounds, p90 67, worst 176 (476 calls), and
 * <b>30.7% over twenty-five</b>. Their file-writing agents occupy eight of the worst ten. A cap that
 * fires on a third of turns is not a safety net that occasionally catches a runaway; it is a bound
 * on normal work, and the failure it produces is worse than a clean one — a lane throwing
 * {@link Exhausted} and re-asking from nothing looks slow rather than broken.
 *
 * <p>Their corpus also settles the calls-per-round question that made the old name misleading:
 * 2.78 tool calls per model turn across 6,121 calls, so a count of calls is nowhere near a count of
 * rounds and the bound never measured what its name suggested.
 *
 * <p>TOKENS ARE WHAT IS ACTUALLY SPENT, and they are what the context fills with, so one number
 * bounds both the money and the wall the conversation is heading for. Every {@link Reply} already
 * carries the server's own count.
 *
 * <p>A SERVER THAT REPORTS NO USAGE CANNOT BE BOUNDED THIS WAY, and pretending otherwise would be
 * the third unfalsifiable check this library has shipped. {@link Spend#NONE} totals zero, which is
 * indistinguishable from a free turn, so {@link #rounds} remains as a backstop for exactly that
 * case — deliberately far above anything real work reaches, and there to stop a loop rather than to
 * shape one.
 *
 * @param tokens prompt and completion together, across the whole of one {@code run}; zero is no bound
 * @param rounds a ceiling on turns for a server that reports no usage; zero is no bound
 */
public record Budget(long tokens, int rounds) {

    public Budget {
        if (tokens < 0 || rounds < 0) {
            throw new IllegalArgumentException("a budget cannot be negative: tokens=" + tokens
                    + " rounds=" + rounds);
        }
    }

    /**
     * TWO MILLION TOKENS AND A THOUSAND TURNS, both far above what finishing work costs.
     *
     * <p>Chosen to be a runaway guard rather than a work bound, which is the mistake the 25 made.
     * The longest healthy agent turn measured in the field is 176 rounds and 476 calls, so a
     * thousand turns is roughly six times the worst real work anyone has reported. The token half
     * is sized the same way: that consumer's heaviest PHASE spends 3.6M across many {@code run()}s,
     * and this bounds one {@code run()}, where they sit well under two million. These are numbers
     * this library picked, so they are numbers a caller should be able to replace — hence
     * {@link #of} and {@link #none()}.
     */
    public static Budget shipped() {
        return new Budget(2_000_000L, 1_000);
    }

    /** A token ceiling of the caller's, with the shipped backstop still under it. */
    public static Budget of(long tokens) {
        return new Budget(tokens, shipped().rounds());
    }

    /**
     * NOTHING STOPS THIS AGENT BUT ITS OWN COMPLETION, which is the right answer when somebody is
     * watching and the wrong one in an unattended sweep. The harness this design follows has no
     * round bound at all, and it has a human and an abort signal that a library call does not.
     */
    public static Budget none() {
        return new Budget(0L, 0);
    }

    public Budget withTokens(long tokens) {
        return new Budget(tokens, rounds);
    }

    public Budget withRounds(int rounds) {
        return new Budget(tokens, rounds);
    }

    /** Whether {@code spent} tokens over {@code turns} turns has passed either ceiling. */
    boolean spent(long spent, int turns) {
        return (tokens > 0 && spent >= tokens) || (rounds > 0 && turns >= rounds);
    }
}
