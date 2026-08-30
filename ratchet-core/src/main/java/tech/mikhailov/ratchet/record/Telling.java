package tech.mikhailov.ratchet.record;

/**
 * HOW MUCH OF EACH LINE THE RUN'S OWN SUMMARY TELLS IT, DECIDED BY THE CALLER.
 *
 * <p>{@link Trace#happened} is the run reading its own record back, and it is read INTO a prompt.
 * Bounding it is right — returning rows whole would put the conversation inside itself. The number
 * was not: 180 characters per line, a private literal with no parameter, in a method that HAS NO
 * CALLER INSIDE THIS LIBRARY. It exists to be called from outside, and the one thing outside could
 * not reach was how much of it survived.
 *
 * <p>WHAT 180 COST, MEASURED BY THE CONSUMER WHO HIT IT. They expose {@code happened} as a
 * {@code what_happened} tool, which is how one stage's agent learns what an earlier stage's agent
 * concluded. Across 482 tool calls in one sweep, 300 of them — 62% — repeated an identical
 * (tool, arguments) pair, and those repeats re-fetched 1,224,450 of 1,479,660 bytes: 83%. In one
 * bump six agents each re-read the same 10,376-byte pom, each having first asked what had happened
 * and been shown 180 characters of the previous read. The clip saved about 10 KB of summary and
 * cost about 50 KB of re-fetching, and it did it by making agents rediscover facts already
 * established — which is the exact failure {@code happened} exists to prevent.
 *
 * <p>SO IT IS A FUNCTION AND NOT A NUMBER, for the reason {@code Keeping} in ratchet-llm is: a bound this library picks is this library deciding, and only the caller knows what its tools
 * return. The kind is passed so a consumer can keep tool results whole and still hold progress
 * notes to a glance.
 *
 * <p>THIS IS A DIFFERENT AXIS FROM {@code Keeping}, which is why it is a separate type here rather
 * than a reuse — and why it must be, since ratchet-core cannot see ratchet-llm. Keeping bounds what the RECORD keeps. This bounds what goes back INTO a prompt,
 * which is the other half of the rule the record side already follows: whole into the record,
 * bounded back into the prompt. The line budget on {@code happened} is a third thing again — it
 * bounds how many lines come back, and the caller has always controlled that.
 */
@FunctionalInterface
public interface Telling {

    /**
     * How many characters of this line's {@code text} survive into the summary.
     *
     * @param kind the row kind it came from: {@code asked}, {@code tool}, {@code applied},
     *             {@code progress}, {@code built} or {@code settled}
     */
    int room(String kind, String text);

    /**
     * THERE IS DELIBERATELY NO {@code shipped()} HERE, and finding out why is the useful part.
     *
     * <p>The two doors this seam serves have different historical defaults — 180 characters in
     * {@link Trace#happened} and 8,000 in ratchet-llm's watcher — so a single "shipped" number
     * would have had to be wrong for one of them. It is not one number badly chosen; it is not a
     * property of this type at all. Each door names its own default at its own call site, with the
     * reason it has that one, and this type only says how to express a bound.
     *
     * <p>WHAT THE EVIDENCE SAYS ABOUT 180, so the next caller need not measure it again — WITH ITS
     * PROVENANCE, because a number in a library outlives the report it came from and the next
     * caller will size their own bound from this paragraph.
     *
     * <p>Two measurements, both from the same consumer, both of TOOL RESULTS AS RETURNED rather
     * than of lines as they reach this bound. They are not the same quantity: {@link Trace#happened}
     * flattens newlines and prefixes each row before clipping, so a result and the line carrying it
     * differ in length. Size a bound from these knowing that.
     *
     * <ul>
     *   <li>Across four run roots, 180,525 results: p50 485, p90 7,492, p99 47,820.
     *   <li>Across one 21-repository sweep, 482 calls: p50 167, p90 8,917, p99 and max 44,605,
     *       with 17 over 20,000.
     * </ul>
     *
     * <p>180 is below the median of both, so nearly every line was a stub. 8,000 sits at the p90 of
     * the second and is what this library means by "enough to see what happened" everywhere else it
     * has had to decide.
     *
     * <p>THE DEFAULT HAS NOT BEEN MOVED, and the reason is worth as much as the number. The door is
     * what was asked for; a bigger summary is paid by every consumer including those who never
     * measured; and what was actually observed is a CORRELATION between a stubbed summary and a
     * re-read, which is not yet proof that raising the bound stops the re-read. That consumer is
     * running the same 21 repositories at 180 and at 8,000 to find out. If your agents re-read
     * files they have already read, {@code Telling.upTo(8_000)} is where to start; the product of
     * this bound and {@link Trace#happened}'s line budget is what to watch, because sixty unbounded
     * lines against a 44,605-character result is a summary that can exceed a context on its own.
     *
     * <p>A bound here is paid PER LINE, and {@link Trace#happened}'s line budget bounds how many
     * lines there are. The two trade against each other and both are the caller's.
     */
    static Telling whole() {
        return (kind, text) -> Integer.MAX_VALUE;
    }

    /** One number for every kind, for a caller who wants the old shape. */
    static Telling upTo(int chars) {
        if (chars < 1) {
            throw new IllegalArgumentException("a bound of " + chars + " tells an agent nothing "
                    + "about a line it is being shown, which is worse than not showing the line");
        }
        return (kind, text) -> chars;
    }

    /** This, with one kind answered by a fixed number instead. */
    default Telling butFor(String kind, int chars) {
        if (chars < 1) {
            throw new IllegalArgumentException("a " + kind + " bound of " + chars
                    + " tells an agent nothing about a line it is being shown");
        }
        return (asked, text) -> kind.equals(asked) ? chars : room(asked, text);
    }
}
