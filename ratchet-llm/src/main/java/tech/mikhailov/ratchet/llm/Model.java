package tech.mikhailov.ratchet.llm;

import java.time.Duration;

import tech.mikhailov.ratchet.config.Env;
import tech.mikhailov.ratchet.record.Trace;

/**
 * The one model, from the environment the process was started with.
 *
 * <p>Temperature zero everywhere: most of the replies in this program are branched on, and a
 * certification that varies between runs certifies nothing.
 *
 * <p>THINKING IS ON, AND IT IS RECORDED. The endpoint runs a reasoning parser, so the model emits
 * its reasoning into a field separate from the content, and a client that reads only the content
 * throws it away. Left alone that reasoning is generated, paid for and discarded, and when a call
 * ends mid-thought the empty content is all anyone downstream sees, with no way to tell a model
 * that declined from one that ran out of room. Measured here, one closed-list critic answer costs
 * 537 completion tokens with thinking on and 3 with it off: switching it off is the cheap answer
 * and the wrong one, because the reasoning is the most informative thing an agent produces and a
 * judgement whose grounds are not recorded cannot be audited or tuned. So it stays on, {@link Wire}
 * reads it off the stream, and an empty answer is re-asked rather than read as agreement.
 *
 * <p>WHAT THIS CLASS NO LONGER DOES is build somebody else's client out of eleven builder calls,
 * two of which existed to work around it. The endpoint is spoken to directly; see {@link Wire}.
 */
public final class Model {

    private Model() {
    }

    /** Producers and critics share a configuration; what differs is what the chain does with them. */
    public static Chat forProducer(Trace trace) {
        return forProducer(trace, Endpoint.fromEnv(), Retry.fromEnv());
    }

    public static Chat forCritic(Trace trace) {
        return forCritic(trace, Endpoint.fromEnv(), Retry.fromEnv());
    }

    /**
     * The same model, pointed where the caller says rather than where the environment says.
     *
     * <p>For a consumer whose credentials are named something else — every sibling repository keeps
     * them as {@code LLM_BASE_URL} and needs a launcher to rename them — and for a pipeline that
     * wants a cheap model for extraction and a better one for judgement in the same process.
     */
    public static Chat forProducer(Trace trace, Endpoint endpoint) {
        return forProducer(trace, endpoint, Retry.fromEnv());
    }

    public static Chat forCritic(Trace trace, Endpoint endpoint) {
        return forCritic(trace, endpoint, Retry.fromEnv());
    }

    /** Everything chosen: where, how it retries, and how it answers. */
    public static Chat forProducer(Trace trace, Endpoint endpoint, Retry retry, Sampling sampling) {
        return build(trace, true, endpoint, retry, sampling, Watch.fromEnv());
    }

    public static Chat forCritic(Trace trace, Endpoint endpoint, Retry retry, Sampling sampling) {
        return build(trace, true, endpoint, retry, sampling, Watch.fromEnv());
    }

    /**
     * Everything chosen, INCLUDING THE TWO LIVENESS BOUNDS.
     *
     * <p>The one overload that reads no environment variable at all, added because a consumer whose
     * patience is a setting on a page (ratchet#7) needs to hand in a {@link Watch} per call and had
     * to construct the client itself to do it.
     */
    public static Chat forProducer(Trace trace, Endpoint endpoint, Retry retry, Sampling sampling,
                                   Watch watch) {
        return build(trace, true, endpoint, retry, sampling, watch);
    }

    public static Chat forProducer(Trace trace, Endpoint endpoint, Retry retry) {
        return build(trace, true, endpoint, retry, Sampling.fromEnv(), Watch.fromEnv());
    }

    public static Chat forCritic(Trace trace, Endpoint endpoint, Retry retry) {
        return build(trace, true, endpoint, retry, Sampling.fromEnv(), Watch.fromEnv());
    }

    /**
     * The same model, with the retry policy chosen by the caller rather than by the environment.
     *
     * <p>For a consumer whose endpoint is not the one these defaults were measured against — a
     * local process, a test, a deployment that would rather fail fast — and for a consumer that
     * wants to test its own pipeline without living through a real wait. See {@link Retry}.
     */
    public static Chat forProducer(Trace trace, Retry retry) {
        return build(trace, true, Endpoint.fromEnv(), retry, Sampling.fromEnv(), Watch.fromEnv());
    }

    public static Chat forCritic(Trace trace, Retry retry) {
        return build(trace, true, Endpoint.fromEnv(), retry, Sampling.fromEnv(), Watch.fromEnv());
    }

    /**
     * The model a RE-ASK uses, with thinking off.
     *
     * <p>Only for the second attempt after a blank. The first attempt keeps thinking, because the
     * reasoning is the point; but a blank means the reasoning entered a cycle it cannot leave under
     * greedy decoding, and asking the same model to think again reproduces it. Measured: the
     * answer-first instruction this replaces ran 80% runaway against a 62.5% control, so the retry
     * was making the second attempt worse than the first. Thinking off measured 0 of 10 runaway at
     * 340 tokens and 17 seconds.
     */
    public static Chat forRetry(Trace trace) {
        return forRetry(trace, Endpoint.fromEnv(), Retry.fromEnv());
    }

    public static Chat forRetry(Trace trace, Retry retry) {
        return build(trace, false, Endpoint.fromEnv(), retry, Sampling.fromEnv(), Watch.fromEnv());
    }

    public static Chat forRetry(Trace trace, Endpoint endpoint, Retry retry) {
        return build(trace, false, endpoint, retry, Sampling.fromEnv(), Watch.fromEnv());
    }

    private static Chat build(Trace trace, boolean thinking, Endpoint endpoint, Retry retry,
                              Sampling sampling, Watch watch) {
        // The same first-non-blank chain as setting(), spelt with Env's own truth rules so there
        // is one place that decides what "yes" means.
        boolean wantThinking = thinking && sampling.thinks() && Env.flag("RATCHET_THINKING",
                Env.flag("OC_THINKING", Env.flag("BJV_THINKING", true)));
        return wrap(Wire.to(endpoint, sampling, watch, wantThinking, trace), trace, retry);
    }

    /**
     * THE CHAIN ITSELF, APART FROM THE CLIENT, SO IT CAN BE ASSERTED WITHOUT AN ENDPOINT.
     *
     * <p>{@link #build} could not be called without a real base URL and model name, which is why the
     * decorators it installs were unprovable: the whole of {@link Retrying} could be deleted from
     * the line below and every test in this module still passed. That is the more expensive half of
     * reading configuration at class load — not that the value is untestable, but that the wiring
     * it feeds is untestable with it.
     *
     * <p>RETRIED BENEATH EVERYTHING ELSE. A transport failure that reaches {@link Insisting} is
     * read as an empty answer, and an empty answer from a critic approves; so the drop is handled
     * here, where it is still recognisably a drop.
     *
     * <p>Package-private no longer: with {@link Chat} being one method, a consumer can hand in
     * anything at all, and the seam that existed for this library's own tests is the same seam a
     * consumer wanted in ratchet#2. It is {@link Retrying#on} that they should reach for; this stays
     * for the tests that assert the chain's shape.
     */
    static Chat wrap(Chat client, Trace trace) {
        return wrap(client, trace, Retry.fromEnv());
    }

    /**
     * The same chain with the three things that take real time handed in.
     *
     * <p>Split out because the production draw is up to a minute wide, and a test that proved the
     * chain by living through one real wait would take that minute — so it would either be deleted
     * or the jitter would be quietly shrunk to keep it fast, and shrinking the jitter is the one
     * change that silently undoes what it is for.
     */
    static Chat wrap(Chat client, Trace trace, Retry retry) {
        return new Retrying(client, retry.attempts(), retry.budget(), retry.backoff(),
                retry.pause(), retry.worthRetrying(), retry.now(), trace);
    }

    /**
     * THE DRAW THAT KEEPS A SWEEP'S LANES APART, up to {@code RATCHET_JITTER_SECONDS}.
     *
     * <p>Every lane of a sweep talks to one endpoint and they fail together when it hiccups. On the
     * bare schedule they would return together too. A minute is wide enough to spread eight lanes
     * across the early waits, where the schedule itself is only a second or two apart.
     */
    static int jitterSeconds() {
        return draw(jitterSpread());
    }

    /** How wide the draw is, as a number rather than a draw, so a caller can pin it. */
    static int jitterSpread() {
        return setting("JITTER_SECONDS", 60);
    }

    /** One draw in {@code [0, spread)}; a spread of zero or less means no draw at all. */
    static int draw(int spread) {
        return spread <= 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextInt(spread);
    }

    /**
     * A WALL-CLOCK BOUND ON A WHOLE RETRY SEQUENCE, because a count of attempts bounds nothing.
     *
     * <p>Ten attempts against a frozen endpoint is ten stalls, and a stall is twenty minutes: three
     * and a half hours in which a lane holds a slot, which is the thing {@link Watch#ceiling()}
     * exists to stop and cannot, because that ceiling is per attempt. Thirty minutes leaves every
     * one of the ten attempts available to the failures that fail fast — the whole jittered
     * schedule is about ten minutes at its worst — and stops the ones that hang.
     */
    static Duration budget() {
        return Duration.ofMinutes(setting("RETRY_BUDGET_MINUTES", 30));
    }

    /**
     * How many times a model call is attempted before the stage is allowed to fail.
     *
     * <p>Settable, because a consumer whose endpoint is a local process on the same host has
     * nothing to gain from eighty-eight seconds of waiting, and one behind a shared proxy may want
     * more. One is a valid answer and means the behaviour before this existed.
     *
     * <p>Read per call rather than once at class load. A static final would be one number this
     * process can never be asked about again, which is the thing that left the two liveness bounds
     * without a test between them.
     */
    static int attempts() {
        return setting("ATTEMPTS", 10);
    }

    /**
     * THE PARSE, AS A PURE FUNCTION, IT DOES NOT THROW, AND IT FALLS BACK TO THE CALLER'S NUMBER.
     *
     * <p>A bad value used to be a {@link NumberFormatException} raised while a class was loading,
     * which surfaces as a {@code NoClassDefFoundError} somewhere unrelated and takes a whole sweep
     * down over a typo in an environment variable. A number nobody can read is a number this falls
     * back from, and the run says what it is doing by doing the documented thing.
     *
     * <p>IT USED TO FALL BACK TO TEN, WHATEVER IT WAS PARSING. One parser served six settings and
     * ten is the default of exactly one of them, so an unreadable value did not do the documented
     * thing, it did {@code ATTEMPTS}'s thing:
     *
     * <pre>
     * RATCHET_CEILING_HOURS=3h        10 hours, not 3      the natural typo for a name in hours
     * RATCHET_THINKING_TOKENS=4k      10 tokens, not 4000
     * RATCHET_RETRY_BUDGET_MINUTES    10 minutes, not 30
     * RATCHET_STALL_MINUTES           10 minutes, not 20
     * RATCHET_JITTER_SECONDS          10 seconds, not 60
     * </pre>
     *
     * <p>The second line is the one that ends runs. Ten thinking tokens is not a smaller budget, it
     * is no budget: every generation is cut off mid-thought and comes back blank, which is the
     * {@link Truncated} failure this library documents at length — arrived at silently, from a typo,
     * with the trace reporting a model that would not answer.
     *
     * <p>Taking the fallback as a number makes it impossible for it to disagree with the default the
     * caller already wrote down two arguments earlier.
     */
    static int numberFrom(String said, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(said.trim()));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** One numeric setting, under its new name or either of the two it used to have. */
    static int setting(String name, int fallback) {
        return numberFrom(setting(name, String.valueOf(fallback)), fallback);
    }

    /**
     * ONE SETTING, UNDER ITS NEW NAME OR EITHER OF THE TWO IT USED TO HAVE.
     *
     * <p>THE OLD NAMES ARE STILL HONOURED, and that is not politeness. A live sweep's lanes were
     * started with an environment this library did not exist to name, from a launcher that cannot
     * be edited while it is running, and a rename that stopped them resolving a model would be a
     * rename that ended the sweep. The new name wins when both are set, so a consumer moves one
     * variable at a time and can see which one is in force.
     */
    static String setting(String name, String fallback) {
        String value = Env.get("RATCHET_" + name);
        if (value == null) {
            value = Env.get("OC_" + name);
        }
        if (value == null) {
            value = Env.get("BJV_" + name);
        }
        return value == null ? fallback : value;
    }
}
