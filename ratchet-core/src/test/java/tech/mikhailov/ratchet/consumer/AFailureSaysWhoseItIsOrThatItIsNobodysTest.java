package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.ratchet.record.JsonlTrace;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SAME DEFECT ONE METHOD ALONG FROM {@code thought}, AND FOUND THE SAME WAY.
 *
 * <p>{@code failed(key, cause)} could not say whose failure it was, while {@link Trace.Event}
 * carries an {@code agent} and {@link Trace#traceEvents} narrows by one. ratchet never calls this
 * itself — its own failing request goes through {@code Listening}, which has the {@code Ask} — so
 * no test here could have noticed, and the report came from the consumer who went looking for the
 * shape after the first one was fixed. Their busiest failure path is a retry reporting a failed
 * attempt: nine rows in six minutes, none able to say whose call was being retried.
 *
 * <p>AND NULL IS A REAL ANSWER, which is the part that is not just the previous fix again. A
 * failure belonging to the run rather than to any one agent has no agent, and writing {@code ""}
 * would stand a nameless agent in the record beside the named ones. So the column is omitted, and
 * these tests assert on the RAW ROW, because that is the only place the difference exists.
 */
class AFailureSaysWhoseItIsOrThatItIsNobodysTest {

    @Test
    void aFailureIsFoundByTheAgentItBelongsTo(@TempDir Path dir) {
        JsonlTrace trace = new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settled.jsonl"),
                "run-1");

        trace.failed("planner", "run-1", new IllegalStateException("the endpoint went away"));

        assertEquals(1, trace.traceEvents("", "planner"));
        assertEquals(0, trace.traceEvents("", "writer"),
                "and it is not the writer's, which before this every failure equally was not");
        List<Trace.Event> found = trace.traceFind("endpoint", "", "planner", 10);
        assertEquals(1, found.size());
        assertEquals("failed", found.get(0).kind());
        assertEquals("planner", found.get(0).agent());
    }

    /**
     * A DISK FILLING UP IS NOBODY'S FAULT IN PARTICULAR, and the record should say that rather than
     * invent an agent with no name to hold the column open.
     */
    @Test
    void aFailureThatBelongsToNobodyOmitsTheColumnRatherThanBlankingIt(@TempDir Path dir)
            throws Exception {
        JsonlTrace trace = new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settled.jsonl"),
                "run-2");

        trace.failed("planner", "run-2", new IllegalStateException("the planner gave up"));
        trace.failed(null, "run-2", new IllegalStateException("the workspace vanished"));

        List<String> rows = Files.readAllLines(dir.resolve("trace.jsonl"));
        Map<String, String> attributed = Json.row(rows.get(0));
        Map<String, String> nobodys = Json.row(rows.get(1));

        assertEquals("planner", attributed.get("agent"), "the first one names somebody");
        assertFalse(nobodys.containsKey("agent"),
                "and the second has no agent column at all rather than an empty one: " + nobodys);

        assertEquals(1, trace.traceEvents("", "planner"),
                "so narrowing finds the attributed one and only it");
        assertEquals(2, trace.traceEvents("", ""), "while an unnarrowed read sees both");
    }

    /**
     * WHAT THE PROJECTION CANNOT CARRY, ASSERTED SO IT CANNOT BE PROMISED BY ACCIDENT.
     *
     * <p>{@link Trace.Event#agent} is a {@code String}, and a String has nowhere to put "no answer".
     * The record distinguishes an absent column from an empty one; the view of it does not, and a
     * caller relying on the distinction needs to know where it stops.
     */
    @Test
    void theEventCannotTellAnAbsentAgentFromABlankOneAndThisSaysSo(@TempDir Path dir) {
        JsonlTrace trace = new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settled.jsonl"),
                "run-3");

        trace.failed(null, "run-3", new IllegalStateException("nobody's"));
        trace.failed("", "run-3", new IllegalStateException("also nobody's"));

        List<Trace.Event> both = trace.traceSlice("", "", 0, 2);
        assertEquals(2, both.size());
        assertTrue(both.stream().allMatch(event -> event.agent().isEmpty()),
                "both read back as the empty string, which is the honest limit of a String column");
    }
}
