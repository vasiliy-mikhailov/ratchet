package tech.mikhailov.ratchet.llm;

import tech.mikhailov.ratchet.record.Retained;

/**
 * WHAT THE MODEL IS SHOWN OF A RESULT TOO BIG TO SEND WHOLE, AND WHERE THE REST WENT.
 *
 * <p>{@link Recording} already keeps the whole result — the corpus gets everything and the prompt
 * gets a bound, which is this library's oldest correct rule. What it could not do was tell the model
 * where the rest is. A magnitude says something is missing; a locator lets the reader go and get it,
 * and the difference decides whether an agent asks again or guesses.
 *
 * <p>THIS LIBRARY DOES NOT OWN THE STORE, AND THAT IS THE WHOLE REASON THIS IS A SEAM. Where a
 * spilled result goes is a fact about the caller's filesystem, its session, its retention and its
 * cleanup — none of which a library called from inside somebody else's program can see. So the
 * caller saves it and composes the notice; this decides only WHEN a result is too big and hands it
 * over.
 *
 * <p>The shape is dsh's {@code spill-policy}, whose notice reads:
 *
 * <pre>
 * (Omitted 41208 bytes. Full formatted result stored at: /…/session-…/a1b2c3d4e5f6-web_fetch.txt.
 *  Use read with offset/limit, or grep this path to search within it.)
 * </pre>
 *
 * <p>Two of their rules are worth taking with it. A locator nothing can visit is a longer way of
 * saying "truncated", so a caller wiring this wants a {@code read} that takes an offset and a
 * {@code grep} in the same tool set. And a spilled result must not be re-spilled by the tool that
 * reads it back, or a {@code read → spill → read again} loop follows.
 */
@FunctionalInterface
public interface Spilling {

    /**
     * @param whole what the tool actually returned, entire
     * @param room  how much of it may travel back to the model
     * @return what the model sees: a bounded preview, and ideally where the rest is
     */
    String kept(String whole, int room);

    /**
     * WHAT EVERY CALLER HAD BEFORE THIS SEAM: a bounded head, the total, and what to do about it.
     *
     * <p>No locator, because there is nowhere to point. This is honest rather than good — the reader
     * is told the size of what is missing and how to ask for less, which is the most that can be
     * said without somewhere to put it.
     */
    static Spilling none() {
        return (whole, room) -> Retained.head(whole, room, "\n")
                .recoverableBy("Narrow the request if you need the rest.").text();
    }

    /**
     * A preview inside {@code room}, with the caller's own locator after it.
     *
     * <p>{@link Retained#within} rather than {@code head}, because here the bound is a real cap on
     * what enters the model's context and the notice must be paid for out of it rather than pushing
     * the result past the ceiling it exists to enforce.
     *
     * @param save given the whole text, stores it and returns a sentence saying where it went and
     *             how to read it — the caller's words, because only the caller knows the tools
     */
    static Spilling to(java.util.function.Function<String, String> save) {
        return (whole, room) -> Retained.within(whole, room).recoverableBy(save.apply(whole)).text();
    }
}
