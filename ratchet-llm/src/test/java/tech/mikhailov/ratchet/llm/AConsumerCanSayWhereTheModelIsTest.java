package tech.mikhailov.ratchet.llm;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE ENDPOINT WAS THE LAST THING ONLY THE ENVIRONMENT COULD SAY.
 *
 * <p>{@link Retry} became a value a consumer hands in at 0.8.0, and the argument then was that a
 * design injected only by the package that owns it is an argument rather than a feature. The
 * endpoint sat one layer below and stayed that way: {@code base}, {@code model} and {@code key} were
 * read inside {@code build}, so a consumer whose credentials are called something else needed a
 * launcher to rename them before the JVM started, and no test could construct a model at all.
 *
 * <p>These are the three things that could not be done before, asserted as a consumer reaches them.
 */
class AConsumerCanSayWhereTheModelIsTest {

    @Test
    void anEndpointCanBeBuiltWithoutTheEnvironmentSayingAnything() {
        Endpoint local = Endpoint.of("http://127.0.0.1:8000/v1", "qwen-3.8-27b-nvfp4");

        assertEquals("http://127.0.0.1:8000/v1", local.base());
        assertEquals("qwen-3.8-27b-nvfp4", local.model());
        assertEquals("", local.key(), "an endpoint that wants no token is a normal endpoint");
    }

    @Test
    void aConsumerMayUseItsOwnNamesForItsOwnCredentials() {
        // What every sibling repository's .env actually holds. Before this, bridging these to
        // RATCHET_BASE needed a launcher, because a JVM cannot set its own environment.
        Endpoint theirs = new Endpoint("https://inference.example.tech/v1", "some-model", "sk-xxx");

        assertEquals("some-model", theirs.model());
        assertEquals("sk-xxx", theirs.key());
    }

    @Test
    void twoEndpointsCanExistInOneProcess() {
        // The reason this matters: thirteen thousand extractions on something cheap and a few
        // hundred judgements on something better, in one run. One set of variables cannot say that.
        Endpoint cheap = Endpoint.of("http://127.0.0.1:8000/v1", "small");
        Endpoint better = Endpoint.of("https://inference.example.tech/v1", "large");

        assertFalse(cheap.secure(), "plain http gets HTTP/1.1");
        assertTrue(better.secure(), "https gets HTTP/2");
        assertEquals("small", cheap.model());
        assertEquals("large", better.model());
    }

    @Test
    void anEndpointWithNowhereToGoIsRefusedAtTheDoor() {
        assertThrows(IllegalArgumentException.class, () -> Endpoint.of("", "a-model"),
                "a base URL of nothing is not a deployment choice, it is a missing setting");
        assertThrows(IllegalArgumentException.class, () -> Endpoint.of("http://x/v1", " "),
                "nor is a blank model name");
    }

    @Test
    void theKeyIsNeverInTheStringForm() {
        // A record's generated toString puts every component in every stack trace and every log
        // line that interpolates it. A bearer token is not a thing to leak that way.
        Endpoint keyed = new Endpoint("https://x/v1", "m", "sk-secret-value");

        assertFalse(keyed.toString().contains("sk-secret-value"), keyed.toString());
        assertTrue(keyed.toString().contains("keyed"), "but it says that there IS one: " + keyed);
        assertTrue(keyed.toString().contains("m"), "and which model, which is the useful half");
    }

    @Test
    void aNullKeyIsAnEmptyKeyRatherThanAFailureLater() {
        assertEquals("", new Endpoint("https://x/v1", "m", null).key(),
                "the client is handed this directly; null there is an NPE a long way from here");
    }

    @Test
    void theRetryPolicyStillComposesWithIt() {
        // The two are independent choices and both are values now. Nothing here asks the environment.
        Endpoint where = Endpoint.of("http://127.0.0.1:8000/v1", "small");
        Retry how = Retry.fibonacciSeconds(3, 0, Duration.ofMinutes(1)).with(Pause.NONE);

        assertEquals(3, how.attempts());
        assertEquals("small", where.model());
    }
}
