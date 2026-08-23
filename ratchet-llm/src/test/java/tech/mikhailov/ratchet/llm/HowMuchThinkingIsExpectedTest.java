package tech.mikhailov.ratchet.llm;

import java.util.List;

import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.record.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE REQUEST SAYS HOW MUCH THINKING IS EXPECTED, WHICH IT NEVER DID.
 *
 * <p>The client set {@code max_tokens} and nothing else. That bounds the OUTPUT; nothing bounded
 * the REASONING, and a reasoning model given a question it cannot answer reasons until something
 * else stops it. What stopped it here was a repetition detector of our own, which aborts the stream
 * and yields NOTHING: 37 times in one sweep, 17% of every agent call, each one costing a retry that
 * frequently derailed the same way.
 *
 * <p>{@code thinking_token_budget} is Qwen's own field and bounds the thinking: at the budget the
 * model is made to stop and write its reply. Measured against this endpoint before it was wired,
 * because a parameter being accepted and being honoured are different claims:
 *
 * <pre>
 * budget 300    reasoning  1,015 chars   content 1,768   finish=stop
 * budget 4,000  reasoning 13,003 chars   content 1,672   finish=stop
 * unbounded     no reply within 300s
 * </pre>
 *
 * <p>The detector stays. A budget bounds how long a cycle runs; it does not stop one starting.
 *
 * <p>THIS TEST EARNED ITS KEEP DURING THE LANGCHAIN4J REMOVAL. The body it reads used to be a map
 * of "custom parameters" handed to somebody else's builder; it is now the whole request, written
 * here. In the first draft of that move the budget was gated on the thinking flag — which reads
 * plausibly and is wrong for the reason {@link #theTemplateSwitchDoesNotDropTheBudget} gives — and
 * this file is what said so.
 */
class HowMuchThinkingIsExpectedTest {

    /**
     * The whole request, as it goes on the wire.
     *
     * <p>Read from the one function that builds it rather than reflected out of a client: the first
     * version of this test dug for a field called {@code model} inside a streaming wrapper, which is
     * not what it is called and would not have survived a library upgrade if it were. Now there is
     * no library and no upgrade — {@link Wire#body} is a pure function of its four arguments.
     */
    private static String body(boolean thinking) {
        return Wire.body(Ask.of(List.of(Said.user("anything"))),
                Endpoint.of("http://test/v1", "a-model"), Sampling.fromEnv(), thinking);
    }

    @Test
    void theBudgetTravelsWithEveryRequest() {
        String sent = body(true);

        assertTrue(sent.contains("\"thinking_token_budget\""),
                "nothing bounded the reasoning, and that is what ran away: " + sent);
        assertEquals(4000, Json.number(sent, "thinking_token_budget", -1),
                "four thousand: where this corpus's aborted generations died, but ending in a reply");
    }

    @Test
    void theTemplateSwitchDoesNotDropTheBudget() {
        // ONE BODY, BUILT ONCE. Each of these was added on a different day for a different reason
        // and they are not alternatives. The template switch is the server's own, and a proxy that
        // drops unknown fields drops it in silence; the budget is what still binds when that
        // happens. Setting the switch INSTEAD of the budget would leave exactly the non-thinking
        // agents with no bound on their reasoning at all.
        String sent = body(false);

        assertTrue(sent.contains("\"thinking_token_budget\""), sent);
        assertTrue(sent.contains("\"chat_template_kwargs\""), sent);
        assertTrue(sent.contains("\"enable_thinking\":false"),
                "the switch is the server template's own, not a prompt asking for brevity: " + sent);
    }

    @Test
    void itIsTheThinkingThatIsBoundedAndTheOutputSeparately() {
        // max_tokens truncates the ANSWER wherever it had got to. The budget makes the model stop
        // thinking and answer. Confusing them is how the previous cap was removed and nothing
        // replaced it: that cap was on the output, so removing it restored no bound on the thinking
        // because there had never been one. Both travel, and they are different numbers.
        String sent = body(true);

        assertEquals(16_000, Json.number(sent, "max_tokens", -1), sent);
        assertEquals(4_000, Json.number(sent, "thinking_token_budget", -1), sent);
        assertNotEquals(Json.number(sent, "max_tokens", -1),
                Json.number(sent, "thinking_token_budget", -1),
                "they bound different things and a single number cannot do both: they share a pool, "
                        + "so the reasoning half must leave room for the answer");
    }
}
