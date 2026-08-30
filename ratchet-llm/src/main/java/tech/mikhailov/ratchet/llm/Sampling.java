package tech.mikhailov.ratchet.llm;

/**
 * HOW THE MODEL SHOULD ANSWER, AS A VALUE THE CALLER CHOOSES.
 *
 * <p>Three numbers this library used to decide on the consumer's behalf, two of them unreachable and
 * one of them impossible to comply with.
 *
 * <p>TEMPERATURE WAS HARDCODED TO ZERO with no setting at all. The reason for zero is real and stays
 * the default — most replies here are branched on, and a certification that varies between runs
 * certifies nothing. But unsloth's own guidance for Qwen 3.8 is that when {@code reasoning_effort}
 * is set the temperature must be 1.0, and a consumer running that model could not comply. A library
 * that hardcodes a value its own endpoint's documentation forbids is not defaulting, it is refusing.
 *
 * <p>THE TWO BUDGETS SHARE A POOL, and that is the server's arrangement rather than this library's:
 * {@code max_completion_tokens} bounds thinking and answer together. What this library can do is let
 * the caller size both halves, and it could not. Measured on this project's endpoint, same prompt,
 * same model:
 *
 * <pre>
 * no reasoning budget        thinking 11,700 ch   answer      0 ch   finish length
 * thinkingTokens = 50        thinking    233 ch   answer  1,261 ch   finish stop
 * </pre>
 *
 * <p>So {@link #thinkingTokens} is the lever that decides whether there is an answer at all, and it
 * was fixed at 4,000 for everyone. Where it does not bind — a proxy that drops unknown fields will
 * swallow it silently — {@link Truncated} is what makes the failure loud instead of
 * returning an empty answer as though somebody had said it.
 *
 * @param temperature    zero for anything branched on; some models require 1.0 with reasoning on
 * @param maxTokens      the whole completion, thinking and answer together — the server pools them
 * @param thinkingTokens the reasoning half, which must leave room in {@code maxTokens} for a reply
 */
public record Sampling(double temperature, int maxTokens, int thinkingTokens) {

    public Sampling {
        if (temperature < 0) {
            throw new IllegalArgumentException("temperature cannot be negative: " + temperature);
        }
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens cannot be negative: " + maxTokens);
        }
        if (thinkingTokens < 0) {
            throw new IllegalArgumentException("thinkingTokens cannot be negative: " + thinkingTokens);
        }
        // ZERO IS NOT A BUDGET OF NOTHING, IT IS NO BUDGET — the field is left off the request and
        // the server decides. A consumer migrating onto this library reported the gap: the client
        // it replaced expressed "let the server decide" by simply omitting max_tokens, and this
        // record could not say it at all, because the guard that refuses a budget too small to
        // answer in also refused the one value that means "do not send one". A guard written to
        // catch a typo took a legitimate state with it.
        if (maxTokens > 0 && thinkingTokens >= maxTokens) {
            throw new IllegalArgumentException("thinkingTokens (" + thinkingTokens + ") must leave "
                    + "room inside maxTokens (" + maxTokens + ") for the answer; they share a pool");
        }
    }

    /** What every existing caller gets, unchanged: {@code RATCHET_THINKING_TOKENS}, and zero. */
    public static Sampling fromEnv() {
        return new Sampling(0.0, 16_000,
                Model.setting("THINKING_TOKENS", 4000));
    }

    /**
     * NO COMPLETION BUDGET AT ALL: {@code max_tokens} is not sent and the server's own default
     * stands.
     *
     * <p>The state a consumer could not express. It is a real one — a deployment that has tuned its
     * server does not want this library overriding it, and a proxy in front of that server may have
     * an opinion of its own. {@link #thinkingTokens} still travels, because that is a separate
     * field and the two are only pooled once a completion budget exists to pool them in.
     *
     * <p>IT IS NOT THE DEFAULT AND SHOULD NOT BE. {@link Truncated} exists because an unbounded
     * budget on this project's endpoint spent 11,700 characters on reasoning and returned no answer
     * at all. Ask for this when you know your server; take {@link #deterministic()} when you do not.
     */
    public static Sampling serverDecides() {
        return new Sampling(0.0, 0, 4_000);
    }

    /** The shipped numbers, spelt out, and not readable from the environment. */
    public static Sampling deterministic() {
        return new Sampling(0.0, 16_000, 4_000);
    }

    /**
     * For a model whose own documentation requires it — Qwen 3.8 with {@code reasoning_effort} set.
     *
     * <p>Named rather than left to {@code new Sampling(1.0, …)} so that a consumer complying with a
     * model's requirement says so, and the next reader does not "fix" it back to zero.
     */
    public static Sampling asTheModelRequires(double temperature) {
        return new Sampling(temperature, 16_000, 4_000);
    }

    public Sampling withTemperature(double temperature) {
        return new Sampling(temperature, maxTokens, thinkingTokens);
    }

    public Sampling withMaxTokens(int maxTokens) {
        return new Sampling(temperature, maxTokens, thinkingTokens);
    }

    /** Lower this when answers come back empty: it is the half that decides whether there is one. */
    public Sampling withThinkingTokens(int thinkingTokens) {
        return new Sampling(temperature, maxTokens, thinkingTokens);
    }

    /** Thinking off entirely. The server's own switch, not a prompt asking for brevity. */
    public boolean thinks() {
        return thinkingTokens > 0;
    }
}
