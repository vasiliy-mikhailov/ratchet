package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import tech.mikhailov.ratchet.record.JsonlTrace;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ATTRIBUTION ON THE ONE ROW THAT NEVER HAD IT, ASSERTED FROM OUTSIDE THE PACKAGE.
 *
 * <p>{@link Trace.Event} has always carried an {@code agent}, and {@link Trace#traceEvents} and
 * {@link Trace#traceFind} have always narrowed by it. {@code thought} was the one writer that could
 * not supply one, so every reasoning row was written unattributed and silently missed every
 * agent-narrowed read — a filter that matched nothing and said so by returning an empty list, which
 * reads exactly like an honest absence.
 *
 * <p>MEASURED TWICE BEFORE ANYONE WROTE THIS TEST. 737 thought rows in one sweep attributed to
 * nobody, recorded in {@code Listening}'s own javadoc and then left unfixed while the two defects
 * beside it were repaired. Then a consumer whose nine agents all appeared in the record as one, who
 * found it by READING a live record rather than by any test failing.
 *
 * <p>Which is the reason this test is written the way it is: it does not assert that a method was
 * called, it asserts that the question a person brings to the record — WHICH of them decided this
 * — can be answered from it.
 */
class AThoughtRowSaysWhichAgentThoughtItTest {

    @Test
    void reasoningIsFoundByTheAgentThatProducedItAndNotByAnother(@TempDir Path dir) {
        JsonlTrace trace = new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settled.jsonl"),
                "run-1");

        trace.thought("planner", "stop", "the BOM has no version for wr-starters", "I will grep");
        trace.thought("writer", "stop", "the fake needs a no-arg constructor", "writing it");

        assertEquals(1, trace.traceEvents("", "planner"),
                "one of the two is the planner's, and narrowing by agent finds exactly it");
        assertEquals(1, trace.traceEvents("", "writer"));
        assertEquals(0, trace.traceEvents("", "nobody-by-that-name"),
                "and an agent that never spoke has no rows, which is what the empty list used to "
                        + "mean for every agent");

        List<Trace.Event> found = trace.traceFind("BOM", "", "planner", 10);
        assertEquals(1, found.size(), "found under the agent that thought it");
        assertEquals("thought", found.get(0).kind());
        assertEquals("planner", found.get(0).agent(),
                "and the row says so itself, rather than the caller having to know");

        assertTrue(trace.traceFind("BOM", "", "writer", 10).isEmpty(),
                "the planner's reasoning is not the writer's, and before this every narrowing "
                        + "agreed with every other by matching nothing");
    }

    /**
     * THE OLD CALL STILL WORKS AND STILL LOSES THE AGENT, which is the honest cost of not breaking
     * every implementation. It is deprecated rather than deleted so the compiler says so at the
     * call site, instead of a record saying so months later.
     */
    @Test
    @SuppressWarnings("deprecation")
    void theThreeArgumentFormStillWritesARowAndStillCannotSayWhose(@TempDir Path dir) {
        JsonlTrace trace = new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settled.jsonl"),
                "run-2");

        trace.thought("stop", "reasoning with nobody's name on it", "the answer");

        assertEquals(1, trace.traceEvents("", ""), "the row is written and readable unnarrowed");
        assertEquals(0, trace.traceEvents("", "planner"),
                "and it belongs to no agent, which is what it always did");
    }
}
