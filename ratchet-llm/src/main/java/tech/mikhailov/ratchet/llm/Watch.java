package tech.mikhailov.ratchet.llm;

import java.time.Duration;

/**
 * HOW LONG A STREAM MAY BE SILENT, AND HOW LONG IT MAY RUN, AS A VALUE.
 *
 * <p>These were {@code private static final Duration} on {@link Wire}, parsed from the
 * environment at class load — the fourth time in this library that a seam stopped one step short of
 * the package boundary, after {@link Backoff}/{@link Pause}, {@link Now} and {@link Endpoint}. Each
 * of those was reported by a consumer rather than found here.
 *
 * <p>WHY A SETTING IS NOT THE SAME AS AN ENVIRONMENT VARIABLE. The consumer that asked for this
 * (ratchet#7) runs a pool of provers with a settings page that writes a file, and every prove
 * re-reads it, so patience can be changed while a 350-marker sweep is running. Restarting a
 * container to change an environment variable kills the pool and orphans every claim in flight —
 * so "just set the env" is a different operation with a different cost, not a smaller version of
 * the same one.
 *
 * @param stall   how long a silent connection may stay silent. Generous, because the first token
 *                can be a long way behind the request on a shared GPU prefilling a large context.
 *                It bounds a DEAD socket, not a slow one.
 * @param ceiling a hard cap that exists only so a wedged lane cannot hold a slot forever. It is the
 *                one guard here that can end work which IS progressing, which is why
 *                {@link GaveUp} is refused by the retry.
 */
public record Watch(Duration stall, Duration ceiling) {

    public Watch {
        if (stall.isNegative() || stall.isZero()) {
            throw new IllegalArgumentException("a stall bound of " + stall + " would end every call");
        }
        if (ceiling.compareTo(stall) <= 0) {
            throw new IllegalArgumentException("the ceiling (" + ceiling + ") must be longer than "
                    + "the stall (" + stall + "), or the ceiling is the only guard that ever fires");
        }
    }

    /** {@code RATCHET_STALL_MINUTES} and {@code RATCHET_CEILING_HOURS}: today's behaviour exactly. */
    public static Watch fromEnv() {
        return new Watch(
                Duration.ofMinutes(Model.setting("STALL_MINUTES", 20)),
                Duration.ofHours(Model.setting("CEILING_HOURS", 3)));
    }

    /** The shipped numbers, spelt out, and not readable from the environment. */
    public static Watch shipped() {
        return new Watch(Duration.ofMinutes(20), Duration.ofHours(3));
    }

    public Watch withStall(Duration stall) {
        return new Watch(stall, ceiling);
    }

    public Watch withCeiling(Duration ceiling) {
        return new Watch(stall, ceiling);
    }
}
