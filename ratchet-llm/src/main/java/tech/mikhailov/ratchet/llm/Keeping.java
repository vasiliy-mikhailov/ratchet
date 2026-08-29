package tech.mikhailov.ratchet.llm;

/**
 * HOW MUCH OF AN EXCHANGE THE RECORD KEEPS, DECIDED BY THE CALLER RATHER THAN BY THIS LIBRARY.
 *
 * <p>{@link Listening} clipped in three places at three literals no caller could reach: the system
 * prompt at 4,000 characters, every other message of the render at 900, and the answer at 900. The
 * marker said {@code (truncated)} and never how much, so in one consumer's corpus the recorded
 * answer column measured p90 = p99 = max = 916 exactly — 900 plus the marker. THE DISTRIBUTION WAS
 * CENSORED AT THE CLIP, and a record that cannot report what it withheld cannot be audited from
 * itself. That consumer had to read token counts out of a different field to measure it.
 *
 * <p>THE FIRST FIX WAS NOT A FIX. It made those three numbers a value with {@code with} methods,
 * which is a settings bag: this library still chose, and a consumer could only argue. The number
 * that had actually been reported was still the number this library shipped, and being able to
 * override it is not the same as it having been decided by anybody who knew the corpus. So this is
 * a FUNCTION, in the shape {@link Backoff} already uses for the same reason — a schedule this
 * library ships and a consumer can replace outright.
 *
 * <p>WHAT THAT BUYS THAT A NUMBER CANNOT. The bound can depend on what is actually there. The same
 * consumer's tools split by three orders of magnitude — {@code edit_file} returns at most 214
 * characters and a {@code grep} returned 3.69 MB — and the ones that CHANGE the world return almost
 * nothing while the ones that only LOOK return megabytes. No single number is right for both, and
 * only the caller knows which is which. A policy sees the column and the text and answers per
 * message.
 *
 * <p>Implement it, or take {@link #shipped()} and narrow one column with {@link #butFor}, or take
 * {@link #everything()} and keep the lot.
 */
@FunctionalInterface
public interface Keeping {

    /**
     * WHICH PART OF THE RECORD IS ASKING.
     *
     * <p>Finer than the three bounds that stood here, because the interesting distinction is inside
     * the render: a tool result and a user's instruction land in the same column and are not the
     * same kind of thing. This is the granularity at which one consumer's field data actually
     * separates.
     */
    enum Column {
        /** The system prompt, written once per agent and named on every call after that. */
        PROMPT,
        /** What the caller said, re-rendered on every later call of the conversation. */
        USER,
        /** What the model said on a previous turn, re-rendered with it. */
        ASSISTANT,
        /** What a tool answered, re-rendered with it, and the widest payload in the record. */
        TOOL_RESULT,
        /** What came back on this call, written once and recorded in no other column. */
        ANSWER
    }

    /**
     * How many characters of {@code text} this column keeps. The text is passed already stripped,
     * so it is exactly what would be written; return {@code text.length()} or more to keep it whole.
     */
    int room(Column column, String text);

    /**
     * THE SHIPPED POLICY, AND EVERY NUMBER IN IT IS ARGUED FROM A CORPUS RATHER THAN INHERITED.
     *
     * <p>256 KB on everything written ONCE — the prompt and the answer. It is a backstop rather
     * than a budget, set where one consumer's field data stops being a distribution and starts
     * being an accident: their largest ordinary payload, a 233 KB dependency listing, sits at 91%
     * of it, and the 3.69 MB beyond it is a {@code grep} whose own median is 126 characters meeting
     * a generated tree. {@link Sampling} ships a 16,000-token completion budget in any case, which
     * is roughly 64,000 characters, so on an answer the model's own budget is the harder bound.
     *
     * <p>8,000 on everything RE-RENDERED — the user, assistant and tool-result turns. Lower,
     * because this is the only text that is paid again: the render re-emits every message on every
     * later call, so it is multiplied by the rounds of a conversation while the other two are
     * written once. That is the 428K-token growth this recorder was shaped against, and it is the
     * one real argument the old 900 had. 8,000 rather than 900 because {@link Asking} already shows
     * a watcher at most 8,000 characters of a tool result and that is the same judgement — enough
     * to see what happened, not enough to replay it — and because 8,000 sits just above the
     * ninetieth percentile of 180,525 measured tool results while 900 sat below the fortieth.
     */
    static Keeping shipped() {
        return (column, text) -> switch (column) {
            case PROMPT, ANSWER -> 262_144;
            case USER, ASSISTANT, TOOL_RESULT -> 8_000;
        };
    }

    /**
     * NOTHING CLIPPED ANYWHERE, WHICH IS A CHOICE AND NOT A DEFAULT.
     *
     * <p>For a consumer that does not wire {@link Recording} and so has no whole copy of a tool
     * result anywhere, and for anyone building a corpus a model will be trained from, where a
     * truncated turn is a training example of a truncated turn.
     *
     * <p>IT REINSTATES THE COST {@link Listening} WAS SHAPED TO AVOID, deliberately: a 3.69 MB tool
     * result is then written again in full on every later call of that conversation. A consumer who
     * wants the answers and briefs whole but not that says
     * {@code Keeping.everything().butFor(Column.TOOL_RESULT, n)}.
     */
    static Keeping everything() {
        return (column, text) -> Integer.MAX_VALUE;
    }

    /** Three fixed numbers, for a caller who wants the old shape: the render covers all three turns. */
    static Keeping of(int prompt, int turn, int answer) {
        atLeastOne("prompt", prompt);
        atLeastOne("turn", turn);
        atLeastOne("answer", answer);
        return (column, text) -> switch (column) {
            case PROMPT -> prompt;
            case ANSWER -> answer;
            case USER, ASSISTANT, TOOL_RESULT -> turn;
        };
    }

    /**
     * {@code RATCHET_RECORD_PROMPT_CHARS}, {@code RATCHET_RECORD_TURN_CHARS} and
     * {@code RATCHET_RECORD_ANSWER_CHARS}.
     *
     * <p>Here for the reason {@link Watch#fromEnv} is: a sweep already running cannot be handed a
     * value, and the deployments this library serves start their lanes from a launcher that cannot
     * be edited while they run. An unreadable value falls back to the shipped number rather than
     * throwing, and {@link Model}'s parse floors every one at 1.
     */
    static Keeping fromEnv() {
        Keeping shipped = shipped();
        return of(Model.setting("RECORD_PROMPT_CHARS", shipped.room(Column.PROMPT, "")),
                Model.setting("RECORD_TURN_CHARS", shipped.room(Column.USER, "")),
                Model.setting("RECORD_ANSWER_CHARS", shipped.room(Column.ANSWER, "")));
    }

    /** This policy, with one column answered by a fixed number instead. */
    default Keeping butFor(Column column, int room) {
        atLeastOne(column.name().toLowerCase(), room);
        return (asked, text) -> asked == column ? room : room(asked, text);
    }

    /**
     * A COLUMN THAT IS ALWAYS EMPTY IS NOT A SMALLER RECORD, it is no record of that half at all.
     *
     * <p>Guarded on the doors that take a number. A policy is free to return zero for a message it
     * has looked at and decided against; what this stops is a zero nobody chose.
     */
    private static void atLeastOne(String column, int room) {
        if (room < 1) {
            throw new IllegalArgumentException("a " + column + " bound of " + room + " writes an "
                    + "empty column on every row, which is not a smaller record but no record of "
                    + "that half at all");
        }
    }
}
