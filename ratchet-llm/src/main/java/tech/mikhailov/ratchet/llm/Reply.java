package tech.mikhailov.ratchet.llm;

import java.util.List;

/**
 * WHAT CAME BACK, INCLUDING THE PARTS THE PREVIOUS CLIENT THREW AWAY.
 *
 * <p>{@link #reasoning()} is the field that argues for this record existing. The server names it
 * {@code reasoning} and the client read {@code reasoning_content}, so the reasoning was generated,
 * paid for and dropped — eighty-four calls produced eighty-four blanks — and recovering it took a
 * transport interceptor wrapped around the client's own SSE parser. A client this library owns
 * reads whatever field the server sends, and the reasoning is simply part of the answer.
 *
 * <p>{@link #spend()} is here for the same reason one level down: it existed only inside a listener
 * context, so the only question it could answer was one somebody had already thought to ask.
 */
public record Reply(String said, String reasoning, List<Called> calls, Ending ending, Spend spend,
                    int dropped) {

    public Reply {
        said = said == null ? "" : said;
        reasoning = reasoning == null ? "" : reasoning;
        calls = calls == null ? List.of() : List.copyOf(calls);
        ending = ending == null ? Ending.OTHER : ending;
        spend = spend == null ? Spend.NONE : spend;
        dropped = Math.max(0, dropped);
    }

    /** What every caller built before 0.19: a turn from which nothing was refused. */
    public Reply(String said, String reasoning, List<Called> calls, Ending ending, Spend spend) {
        this(said, reasoning, calls, ending, spend, 0);
    }

    /** The model ended its turn asking for tools. */
    public boolean wantsTools() {
        return !calls.isEmpty();
    }

    /**
     * THE GENERATION WAS CUT MID-CALL AND THE HALF-WRITTEN CALLS WERE REFUSED.
     *
     * <p>A count rather than a flag, for the reason every other bound in this library now carries
     * one: a reader told that something was dropped and not how much cannot tell one truncated call
     * from twelve.
     *
     * <p>{@link #wantsTools()} is false here even though the model asked for tools, which is the
     * whole point — the ask was not finished, so there is nothing to run. A caller that only checks
     * {@code wantsTools()} will read this as an ordinary answer, so check this first.
     */
    public boolean cutMidCall() {
        return dropped > 0;
    }
}
