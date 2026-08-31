package tech.mikhailov.ratchet.llm;

/**
 * THE AGENT SPENT ITS BUDGET WITHOUT ANSWERING, AND ASKING AGAIN COSTS THE WHOLE BUDGET AGAIN.
 *
 * <p>A type rather than a message, for the reason {@link GaveUp} and {@link Truncated} are types:
 * {@link Retrying#transportFailures()} has to decide whether a second attempt could answer
 * differently, and it must not decide by matching on English.
 *
 * <p>THIS ONE IS DETERMINISTIC AND THE COST OF GETTING IT WRONG IS MULTIPLICATIVE. A retry re-runs
 * the whole conversation from nothing to reach the same wall, so ten attempts is ten conversations
 * to learn one fact. Every other failure this package refuses costs one attempt to rediscover; this
 * one costs a conversation. Measured: the run that raised this had spent 2,052,727 tokens over 36
 * turns, so a retried lane would have paid that again for the same answer.
 *
 * <p>WHAT IT NO LONGER MEANS. This said "round budget" and "fires after twenty-five rounds" for as
 * long as {@code MAX_ROUNDS} existed, and {@link Budget} replaced that in 0.20.0 with a token
 * ceiling and a rounds backstop far above real work. The prose outlived the bound it described, and
 * a consumer reading it would have gone looking for a round cap that was not there. It is a token
 * ceiling that fires in practice; rounds are the backstop for a server that reports no usage.
 *
 * <p>It matters because of the door ratchet#8 opened. In the shipped chain {@code Asking} sits ABOVE
 * {@code Retrying}, so this never reached the predicate — but {@link Retrying#around} exists so a
 * consumer can wrap something that is not a {@link Chat}, and its own javadoc names a tool
 * invocation and a third-party agent runtime as the cases. A consumer wrapping their agent loop in
 * it inherited a retry of the one failure that cannot be retried.
 *
 * <p>Extends {@link IllegalStateException} deliberately: this was that type for the life of the
 * class, both callers catch {@code RuntimeException} and record "unreachable", and the message is
 * unchanged. Nothing that used to catch it stops catching it.
 */
public final class Exhausted extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public Exhausted(String because) {
        super(because);
    }
}
