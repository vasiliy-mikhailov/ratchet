package tech.mikhailov.ratchet.llm;

/**
 * THE AGENT SPENT ITS ROUND BUDGET WITHOUT ANSWERING, AND ASKING AGAIN COSTS THE WHOLE BUDGET AGAIN.
 *
 * <p>A type rather than a message, for the reason {@link GaveUp} and {@link Truncated} are types:
 * {@link Retrying#transportFailures()} has to decide whether a second attempt could answer
 * differently, and it must not decide by matching on English.
 *
 * <p>THIS ONE IS DETERMINISTIC AND THE COST OF GETTING IT WRONG IS MULTIPLICATIVE. The bound fires
 * after twenty-five rounds of tool calls, and the retry would re-run all twenty-five from nothing —
 * so ten attempts is two hundred and fifty rounds of model calls to reach the same wall. Every other
 * failure this package refuses costs one attempt to rediscover; this one costs a conversation, and
 * the busiest recorded conversation fitted 465 tool calls inside that budget.
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
