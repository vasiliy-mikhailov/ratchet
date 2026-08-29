package tech.mikhailov.ratchet.llm;

/**
 * HOW MUCH OF AN EXCHANGE THE RECORD KEEPS, AS A VALUE THE CALLER CHOOSES.
 *
 * <p>{@link Listening} clipped in three places at three numbers and no caller could reach any of
 * them: the system prompt at 4,000 characters, every other message of the outbound render at 900,
 * and the answer at 900. The marker it wrote said {@code (truncated)} and never how much, and that
 * is why this stood for eight releases. In one consumer's corpus the recorded answer column
 * measures p90 = p99 = max = 916 characters exactly, which is 900 plus the sixteen characters of
 * the marker. THE DISTRIBUTION IS CENSORED AT THE CLIP: a record cannot report the size of what it
 * withheld, so the one column that would have shown the bound was wrong is the column the bound had
 * already flattened. That consumer had to read token counts from another field to measure it.
 *
 * <p>WHAT THE UNCENSORED COLUMNS SAY. The same consumer's 180,525 tool results, which
 * {@link Recording} writes whole one layer down at the executor, run p50 485, p90 7,492, p99 47,820
 * and one maximum of 3.69 MB. Its 13,486 system prompts run p50 7,394, p90 46,546, p99 81,107. So
 * 900 cut 41.4% of all tool results, and 4,000 kept barely half the median brief in the column that
 * exists to show WHICH instruction was in force.
 *
 * <p>THE FEAR IS REAL AND IT APPLIES TO ONE OF THE THREE. {@link Listening} summarises because a
 * prompt in this corpus grew monotonically to 428K tokens, and writing every request whole would be
 * that growth on disk, squared over a sweep. That squaring is a fact about {@link #turn} alone: the
 * outbound render re-emits every message of the conversation on EVERY call, so a message from round
 * three is written again in every round after it. {@link #prompt} is written once per agent per run
 * and {@link #answer} once per call, and neither is ever written again.
 *
 * <p>SO THESE ARE NOT ONE NUMBER, AND THE AXIS IS NOT THE ONE {@link Recording} DIVIDES ON. That
 * rule is WHOLE INTO THE RECORD, BOUNDED BACK INTO THE PROMPT, and every column here is on the
 * record side of it. The question that separates these three is written ONCE against written AGAIN.
 * The two written once are held to a backstop only a pathological payload reaches. The one written
 * again keeps a real bound, because its job is to let a reader RECOGNISE a conversation rather than
 * reproduce it, and because what it cuts most, a tool result, already has a whole copy in the
 * record one layer down.
 *
 * <p>A CONSUMER WHO HAS NO SUCH COPY, or who wants the whole thing regardless, says so with
 * {@link #everything()}. That is the request this type exists to answer, and until it existed the
 * answer was to fork the library.
 *
 * @param prompt the system prompt, written once per agent and named on every call after that
 * @param turn   every other message of the outbound render, written again on every later call
 * @param answer what came back, written once per call and recorded in no other column
 */
public record Keeping(int prompt, int turn, int answer) {

    /**
     * 256 KB, AND IT IS A BACKSTOP RATHER THAN A BUDGET.
     *
     * <p>Chosen where the field data stops being a distribution and starts being an accident. At
     * this bound three of one consumer's eighteen tools are cut at all, and the largest ordinary
     * payload measured, a 233 KB dependency listing, sits at 91% of it, so the number is above the
     * distribution rather than through it. The 3.69 MB result beyond it is a {@code grep} whose own
     * median is 126 characters meeting a generated tree: no per-tool number catches that, and no
     * smaller global one leaves the ordinary payloads whole.
     *
     * <p>It is wider than any answer a shipped run can produce in any case. {@link Sampling} ships
     * a 16,000-token completion budget, which is roughly 64,000 characters, so on {@link #answer}
     * the completion budget is already the harder bound and this only catches a server that
     * ignores it.
     */
    private static final int BACKSTOP = 262_144;

    /**
     * The render's bound, left exactly where it was, and the only one of the three that is paid
     * again on every later call of the same conversation.
     */
    private static final int RENDER = 900;

    public Keeping {
        atLeastOne("prompt", prompt);
        atLeastOne("turn", turn);
        atLeastOne("answer", answer);
    }

    /**
     * A COLUMN THAT IS ALWAYS EMPTY IS NOT A SMALLER RECORD. It is the mutant this recorder's own
     * tests exist to kill, and zero is the one setting that would ship it deliberately.
     */
    private static void atLeastOne(String column, int bound) {
        if (bound < 1) {
            throw new IllegalArgumentException("a " + column + " bound of " + bound + " writes an "
                    + "empty column on every row, which is not a smaller record but no record of "
                    + "that half at all");
        }
    }

    /**
     * {@code RATCHET_RECORD_PROMPT_CHARS}, {@code RATCHET_RECORD_TURN_CHARS} and
     * {@code RATCHET_RECORD_ANSWER_CHARS}: the shipped numbers unless a deployment says otherwise.
     *
     * <p>Here for the reason {@link Watch#fromEnv} is: a sweep already running cannot be handed a
     * value, and the deployments this library serves start their lanes from a launcher that cannot
     * be edited while they run. An unreadable value falls back to the shipped number rather than
     * throwing, and {@link Model}'s parse floors every one of them at 1, so no environment can
     * produce a bound the constructor above would refuse.
     */
    public static Keeping fromEnv() {
        return new Keeping(
                Model.setting("RECORD_PROMPT_CHARS", BACKSTOP),
                Model.setting("RECORD_TURN_CHARS", RENDER),
                Model.setting("RECORD_ANSWER_CHARS", BACKSTOP));
    }

    /** The shipped numbers, spelt out, and not readable from the environment. */
    public static Keeping shipped() {
        return new Keeping(BACKSTOP, RENDER, BACKSTOP);
    }

    /**
     * NOTHING CLIPPED IN ANY COLUMN, WHICH IS A CHOICE AND NOT A DEFAULT.
     *
     * <p>For a consumer that does not wire {@link Recording} and so has no whole copy of a tool
     * result anywhere, and for anyone building a corpus a model will be trained from, where a
     * truncated turn is a training example of a truncated turn.
     *
     * <p>IT REINSTATES THE COST {@link Listening} WAS SHAPED TO AVOID, deliberately: with the
     * render unbounded, a 3.69 MB tool result is written again in full on every later call of that
     * conversation. That is the 428K-token prompt squared over a sweep, on request. A consumer who
     * wants the answers and the briefs whole but not that says
     * {@code Keeping.everything().withTurn(n)}.
     */
    public static Keeping everything() {
        return new Keeping(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public Keeping withPrompt(int prompt) {
        return new Keeping(prompt, turn, answer);
    }

    /** The one bound that is paid again on every later call of the same conversation. */
    public Keeping withTurn(int turn) {
        return new Keeping(prompt, turn, answer);
    }

    public Keeping withAnswer(int answer) {
        return new Keeping(prompt, turn, answer);
    }
}
