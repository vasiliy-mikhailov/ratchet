package tech.mikhailov.ratchet.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE REQUEST SAYS HOW MUCH THINKING IS EXPECTED, WHICH IT NEVER DID.
 *
 * <p>This client set {@code max_tokens} and nothing else. That bounds the OUTPUT; nothing bounded
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
 */
class HowMuchThinkingIsExpectedTest {

    /**
     * What travels in the request body beside the standard fields.
     *
     * <p>Read from the one method that builds it rather than reflected out of the client: the first
     * version of this test dug for a field called {@code model} inside the streaming wrapper, which
     * is not what it is called and would not have survived a library upgrade if it were.
     */
    private static Map<String, Object> customParameters(boolean thinking) {
        return Model.extras(thinking, Sampling.fromEnv());
    }

    @Test
    void theBudgetTravelsWithEveryRequest() {
        Map<String, Object> extra = customParameters(true);

        assertTrue(extra.containsKey("thinking_token_budget"),
                "nothing bounded the reasoning, and that is what ran away: " + extra.keySet());
        assertEquals(4000, ((Number) extra.get("thinking_token_budget")).intValue(),
                "four thousand: where this corpus's aborted generations died, but ending in a reply");
    }

    @Test
    void theTemplateSwitchDoesNotDropTheBudget() {
        // ONE MAP, SET ONCE. Each of these was added on a different day for a different reason, and
        // the builder takes the whole map, so setting the template switch on its own would have
        // silently removed the budget from every non-thinking agent.
        Map<String, Object> extra = customParameters(false);

        assertTrue(extra.containsKey("thinking_token_budget"), extra.keySet().toString());
        assertTrue(extra.containsKey("chat_template_kwargs"), extra.keySet().toString());
    }

    @Test
    void itIsTheThinkingThatIsBoundedNotTheOutput() {
        // max_tokens truncates the ANSWER wherever it had got to. This one makes the model stop
        // thinking and answer. Confusing them is how the previous cap was removed and nothing
        // replaced it: that cap was on the output, so removing it restored no bound on the thinking
        // because there had never been one.
        Map<String, Object> extra = customParameters(true);

        assertFalse(extra.containsKey("max_tokens"),
                "max_tokens is a builder field, not a custom parameter, and bounds a different thing");
    }
}
