package tech.mikhailov.ratchet.llm;

/**
 * WHERE THE MODEL IS, AS A VALUE A CONSUMER CAN HAND IN.
 *
 * <p>Until this existed the endpoint was read inside {@link Model#forProducer}, out of
 * {@code RATCHET_BASE} / {@code OC_BASE} / {@code BJV_BASE}, and there was no way to pass one. That
 * is the same shape of gap {@link Retry} had before 0.8.0 — everything replaceable, nothing
 * replaceable from outside — one layer further down, and it cost three things that were all real:
 *
 * <ul>
 * <li><strong>A consumer could not use its own names.</strong> Every sibling repository keeps its
 *     credentials as {@code LLM_BASE_URL} / {@code LLM_MODEL} / {@code LLM_API_KEY}, so each one
 *     needs a launcher that exports them under ratchet's names before the JVM starts. A JVM cannot
 *     set its own environment, so the bridge cannot live in the program that needs it. Two
 *     consumers wrote that bridge separately.
 * <li><strong>One process, one endpoint.</strong> A pipeline that wants a cheap model for thirteen
 *     thousand extractions and a better one for the folds could not have both, because
 *     {@code forProducer} and {@code forCritic} read the same three variables.
 * <li><strong>A model could not be built in a test at all.</strong> {@code Model.wrap} exists as a
 *     package-private seam precisely because of this; the workaround is older than the diagnosis.
 * </ul>
 *
 * <p>{@link #fromEnv()} is what every existing caller gets, unchanged. Nothing about the defaults
 * moves.
 *
 * @param base  an OpenAI-compatible chat endpoint
 * @param model the model name that endpoint serves
 * @param key   the bearer token, empty where the endpoint wants none
 */
public record Endpoint(String base, String model, String key) {

    public Endpoint {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("an endpoint needs a base URL");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("an endpoint needs a model name");
        }
        key = key == null ? "" : key;
    }

    /**
     * The three settings, under their new names or either of the two they used to have.
     *
     * <p>Throws the same messages {@code build} threw before this type existed, because a
     * deployment that has set nothing should not learn a new sentence for it.
     */
    public static Endpoint fromEnv() {
        String base = Model.setting("BASE", null);
        if (base == null) {
            throw new IllegalStateException(
                    "RATCHET_BASE must be set to an OpenAI-compatible chat endpoint");
        }
        String model = Model.setting("MODEL", null);
        if (model == null) {
            throw new IllegalStateException("RATCHET_MODEL must be set");
        }
        return new Endpoint(base, model, Model.setting("KEY", ""));
    }

    /** An endpoint that wants no token. */
    public static Endpoint of(String base, String model) {
        return new Endpoint(base, model, "");
    }

    /**
     * HTTP/2 for TLS, HTTP/1.1 otherwise.
     *
     * <p>On the endpoint rather than in {@code build}, because it is a fact about the endpoint and
     * an endpoint handed in from outside deserves the same treatment as one read from the
     * environment.
     */
    boolean secure() {
        return base.startsWith("https://");
    }

    /** Never the key. A record's generated toString would put a bearer token in every stack trace. */
    @Override
    public String toString() {
        return "Endpoint[" + model + " at " + base + (key.isEmpty() ? "" : ", keyed") + "]";
    }
}
