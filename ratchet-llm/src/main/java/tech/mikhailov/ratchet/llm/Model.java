package tech.mikhailov.ratchet.llm;

import java.net.http.HttpClient;
import java.time.Duration;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import tech.mikhailov.ratchet.config.Env;
import tech.mikhailov.ratchet.record.Trace;

/**
 * The one model, from the environment the process was started with.
 *
 * <p>Temperature zero everywhere: most of the replies in this program are branched on, and a
 * certification that varies between runs certifies nothing.
 *
 * <p>THINKING IS ON, AND IT IS RECORDED. The endpoint runs a reasoning parser, so the model emits
 * its reasoning into a field separate from the content, and the runtime returns only the content.
 * Left alone that reasoning is generated, paid for and thrown away, and when a call ends mid-thought
 * the empty content is all anyone downstream sees, with no way to tell a model that declined from
 * one that ran out of room. Measured here, one closed-list critic answer costs 537 completion
 * tokens with thinking on and 3 with it off: switching it off is the cheap answer and the wrong
 * one, because the reasoning is the most informative thing an agent produces and a judgement whose
 * grounds are not recorded cannot be audited or tuned. So it stays on, {@link Streamed} captures it
 * off the stream, and an empty answer is re-asked rather than read as agreement.
 */
public final class Model {

    private static final int MAX_TOKENS = 16_000;

    /**
     * HOW MUCH THINKING IS EXPECTED, WHICH THIS NEVER SAID.
     *
     * <p>{@code thinking_token_budget} is Qwen's own field and it bounds the REASONING: at the
     * budget the model is made to stop thinking and write its reply, rather than being cut off
     * wherever it had got to. {@code max_tokens} bounds the OUTPUT, which is a different thing.
     * Having one and not the other is what this had, and it is why a reasoning model given a
     * question it cannot answer reasons until something else stops it.
     *
     * <p>Measured on this endpoint before wiring it, because a parameter being ACCEPTED and being
     * HONOURED are different claims and vLLM ignores unknown fields silently:
     *
     * <pre>
     * budget 300    reasoning  1,015 chars   content 1,768   finish=stop
     * budget 4,000  reasoning 13,003 chars   content 1,672   finish=stop
     * unbounded     no reply within 300s
     * </pre>
     *
     * <p>Four thousand because that is where the answers still arrive: one corpus's aborted
     * generations died at a median 14,553 characters of reasoning, which is about this budget, and
     * every one of them returned NOTHING. Bounded, the same length of thinking ends in a reply.
     *
     * <p>THE RUNAWAY DETECTOR STAYS. This bounds how long a cycle can run; it does not stop the
     * model entering one, and a cycle inside the budget still wastes the budget. The two are
     * complementary and the detector is now the rarer of the two.
     */
    private static final int THINKING_TOKENS = Integer.parseInt(setting("THINKING_TOKENS", "4000"));

    /**
     * The transport's own timeout, deliberately set beyond every other guard.
     *
     * <p>THIS MUST NEVER BE THE THING THAT FIRES. The client sets it as the JDK request timeout,
     * from a method shared by the blocking and streaming paths, so on a stream it bounds the wait
     * for the response to begin. That reading is right, and the cost of it being wrong is a hard
     * cut through a generation that was working, which is the exact failure this chain already
     * paid for once at twelve minutes. So it sits above {@link Streamed}'s ceiling rather than
     * anywhere near it: whichever guard is correct, the one that fires is the one that watches for
     * SILENCE, not the one that counts elapsed time.
     */
    private static final Duration PATIENCE = Duration.ofMinutes(
            Integer.parseInt(setting("PATIENCE_MINUTES", "240")));

    private Model() {
    }

    /** Producers and critics share a configuration; what differs is what the chain does with them. */
    public static ChatModel forProducer(Trace trace) {
        return forProducer(trace, Endpoint.fromEnv(), Retry.fromEnv());
    }

    public static ChatModel forCritic(Trace trace) {
        return forCritic(trace, Endpoint.fromEnv(), Retry.fromEnv());
    }

    /**
     * The same model, pointed where the caller says rather than where the environment says.
     *
     * <p>For a consumer whose credentials are named something else — every sibling repository keeps
     * them as {@code LLM_BASE_URL} and needs a launcher to rename them — and for a pipeline that
     * wants a cheap model for extraction and a better one for judgement in the same process.
     */
    public static ChatModel forProducer(Trace trace, Endpoint endpoint) {
        return forProducer(trace, endpoint, Retry.fromEnv());
    }

    public static ChatModel forCritic(Trace trace, Endpoint endpoint) {
        return forCritic(trace, endpoint, Retry.fromEnv());
    }

    /** Everything chosen: where, how it retries, and how it answers. */
    public static ChatModel forProducer(Trace trace, Endpoint endpoint, Retry retry,
                                        Sampling sampling) {
        return build(trace, true, endpoint, retry, sampling);
    }

    public static ChatModel forCritic(Trace trace, Endpoint endpoint, Retry retry,
                                      Sampling sampling) {
        return build(trace, true, endpoint, retry, sampling);
    }

    public static ChatModel forProducer(Trace trace, Endpoint endpoint, Retry retry) {
        return build(trace, true, endpoint, retry, Sampling.fromEnv());
    }

    public static ChatModel forCritic(Trace trace, Endpoint endpoint, Retry retry) {
        return build(trace, true, endpoint, retry, Sampling.fromEnv());
    }

    /**
     * The same model, with the retry policy chosen by the caller rather than by the environment.
     *
     * <p>For a consumer whose endpoint is not the one these defaults were measured against — a
     * local process, a test, a deployment that would rather fail fast — and for a consumer that
     * wants to test its own pipeline without living through a real wait. See {@link Retry}.
     */
    public static ChatModel forProducer(Trace trace, Retry retry) {
        return build(trace, true, Endpoint.fromEnv(), retry, Sampling.fromEnv());
    }

    public static ChatModel forCritic(Trace trace, Retry retry) {
        return build(trace, true, Endpoint.fromEnv(), retry, Sampling.fromEnv());
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
    public static ChatModel forRetry(Trace trace) {
        return forRetry(trace, Endpoint.fromEnv(), Retry.fromEnv());
    }

    public static ChatModel forRetry(Trace trace, Retry retry) {
        return build(trace, false, Endpoint.fromEnv(), retry, Sampling.fromEnv());
    }

    public static ChatModel forRetry(Trace trace, Endpoint endpoint, Retry retry) {
        return build(trace, false, endpoint, retry, Sampling.fromEnv());
    }

    private static ChatModel build(Trace trace, boolean thinking, Endpoint endpoint,
                                   Retry retry, Sampling sampling) {
        // The same first-non-blank chain as setting(), spelt with Env's own truth rules so there
        // is one place that decides what "yes" means.
        boolean wantThinking = thinking && sampling.thinks() && Env.flag("RATCHET_THINKING",
                Env.flag("OC_THINKING", Env.flag("BJV_THINKING", true)));
        HttpClient.Version version = endpoint.secure()
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;
        var jdk = new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(version));
        // STREAMED, so the guard can be time since the last token rather than time for the whole
        // request. A blocking client sends and receives nothing while the server prefills a large
        // context, which is exactly when a proxy reaps the socket and a total timeout fires on work
        // that is progressing.
        var s = OpenAiStreamingChatModel.builder()
                // The reasoning is read off the deltas, because the server names the field
                // `reasoning` and the client looks for `reasoning_content` on this path too.
                .httpClientBuilder(trace == null ? jdk : Reasoning.tee(jdk, trace))
                .baseUrl(endpoint.base())
                .apiKey(endpoint.key())
                .modelName(endpoint.model())
                .temperature(sampling.temperature())
                .maxTokens(sampling.maxTokens())
                .timeout(PATIENCE)
                .returnThinking(Boolean.TRUE);
        // UNDER EVERYTHING THE HARNESS CHOOSES TO RECORD. The listener sees the request as sent and
        // the response as received, including calls that error before any answer exists, so the
        // trace stops being a curated account and becomes the wire.
        if (trace != null) {
            s.listeners(java.util.List.of(new Listening(trace)));
        }

        java.util.Map<String, Object> extra = extras(wantThinking, sampling);
        if (!extra.isEmpty()) {
            s.customParameters(extra);
        }
        return wrap(s.build(), trace, retry);
    }

    /**
     * THE CHAIN ITSELF, APART FROM THE CLIENT, SO IT CAN BE ASSERTED WITHOUT AN ENDPOINT.
     *
     * <p>{@link #build} cannot be called without a real base URL and model name, which is why the
     * decorators it installs were unprovable: the whole of {@link Retrying} could be deleted from
     * the line below and every test in this module still passed. That is the more expensive half of
     * reading configuration at class load — not that the value is untestable, but that the wiring
     * it feeds is untestable with it.
     *
     * <p>RETRIED BENEATH EVERYTHING ELSE. A transport failure that reaches {@link Insisting} is
     * read as an empty answer, and an empty answer from a critic approves; so the drop is handled
     * here, where it is still recognisably a drop.
     */
    static ChatModel wrap(dev.langchain4j.model.chat.StreamingChatModel streaming, Trace trace) {
        return wrap(streaming, trace, Retry.fromEnv());
    }

    /**
     * The same chain with the three things that take real time handed in.
     *
     * <p>Split out because the production draw is up to a minute wide, and a test that proved the
     * chain by living through one real wait would take that minute — so it would either be deleted
     * or the jitter would be quietly shrunk to keep it fast, and shrinking the jitter is the one
     * change that silently undoes what it is for.
     */
    static ChatModel wrap(dev.langchain4j.model.chat.StreamingChatModel streaming, Trace trace,
                          Retry retry) {
        return new Retrying(new Streamed(streaming, trace), retry.attempts(), retry.budget(),
                retry.backoff(), retry.pause(), retry.worthRetrying(), retry.now(), trace);
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
        return attemptsFrom(setting("JITTER_SECONDS", "60"));
    }

    /** One draw in {@code [0, spread)}; a spread of zero or less means no draw at all. */
    static int draw(int spread) {
        return spread <= 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextInt(spread);
    }

    /**
     * A WALL-CLOCK BOUND ON A WHOLE RETRY SEQUENCE, because a count of attempts bounds nothing.
     *
     * <p>Ten attempts against a frozen endpoint is ten stalls, and a stall is twenty minutes: three
     * and a half hours in which a lane holds a slot, which is the thing {@link Streamed}'s ceiling
     * exists to stop and cannot, because that ceiling is per attempt. Thirty minutes leaves every
     * one of the ten attempts available to the failures that fail fast — the whole jittered
     * schedule is about ten minutes at its worst — and stops the ones that hang.
     */
    static Duration budget() {
        return Duration.ofMinutes(attemptsFrom(setting("RETRY_BUDGET_MINUTES", "30")));
    }

    /**
     * How many times a model call is attempted before the stage is allowed to fail.
     *
     * <p>Settable, because a consumer whose endpoint is a local process on the same host has
     * nothing to gain from eighty-eight seconds of waiting, and one behind a shared proxy may want
     * more. One is a valid answer and means the behaviour before this existed.
     *
     * <p>Read per call rather than once at class load. A static final would be one number this
     * process can never be asked about again, which is the thing that left {@link Streamed}'s two
     * bounds without a test between them.
     */
    static int attempts() {
        return attemptsFrom(setting("ATTEMPTS", "10"));
    }

    /**
     * THE PARSE, AS A PURE FUNCTION, AND IT DOES NOT THROW.
     *
     * <p>A bad value used to be a {@link NumberFormatException} raised while the class was loading,
     * which surfaces as a {@code NoClassDefFoundError} somewhere unrelated and takes a whole sweep
     * down over a typo in an environment variable. A number nobody can read is a number this falls
     * back from, and the run says what it is doing by doing the documented thing.
     */
    static int attemptsFrom(String said) {
        try {
            return Math.max(1, Integer.parseInt(said.trim()));
        } catch (NumberFormatException notANumber) {
            return 10;
        }
    }

    /**
     * WHAT TRAVELS IN THE REQUEST BODY BESIDE THE STANDARD FIELDS.
     *
     * <p>ONE MAP, BUILT ONCE, because the builder takes the whole thing: setting the template switch
     * on its own would drop the budget, and the two were added on different days for different
     * reasons. A test can read this without reflecting into the client's internals, which is the
     * only way to check it that survives a library upgrade.
     */
    static java.util.Map<String, Object> extras(boolean thinking, Sampling sampling) {
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
        if (sampling.thinkingTokens() > 0) {
            extra.put("thinking_token_budget", sampling.thinkingTokens());
        }
        if (!thinking) {
            // The server template's own switch, not a prompt asking for brevity: an instruction
            // to answer first was measured to increase the runaway rate, not reduce it.
            extra.put("chat_template_kwargs", java.util.Map.of("enable_thinking", Boolean.FALSE));
        }
        return extra;
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
