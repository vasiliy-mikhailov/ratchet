package tech.mikhailov.ratchet.llm;

/**
 * WHAT THE CALL COST, IN TOKENS, AS THE SERVER COUNTED THEM.
 *
 * <p>First class rather than reconstructed, because the previous client only surfaced this through
 * a listener context and one corpus needed it for a question a character count cannot answer: what
 * the thinking budget ACTUALLY spent, as against what it was set to. The endpoint sends it in a
 * final chunk with an empty {@code choices} array, which costs one extra request field
 * ({@code stream_options.include_usage}) and nothing else.
 *
 * <p>{@link #NONE} rather than null, so a caller adding up a sweep never has to ask.
 */
public record Spend(int prompt, int completion) {

    public static final Spend NONE = new Spend(0, 0);

    public int total() {
        return prompt + completion;
    }
}
