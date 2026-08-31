package tech.mikhailov.ratchet.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.ratchet.record.JsonlTrace;
import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ROW KIND WITH NO READER VANISHES, AND VANISHES QUIETLY, WHICH IS WHY THIS TEST EXISTS.
 *
 * <p>Both readers in {@code JsonlTrace} are switches ending in {@code default -> ""}, and both
 * callers skip a blank line. So a kind the writer produces and the switch does not name is not
 * rendered badly — it is ABSENT, with no error anywhere, from a record whose entire purpose is
 * being read later.
 *
 * <p>IT HAD ALREADY HAPPENED, TO THE MOST IMPORTANT KIND THERE IS. {@code failed} was written by
 * {@code JsonlTrace} and named by NEITHER switch, so no failure had ever appeared in
 * {@code traceEvents}, {@code traceSlice}, {@code traceFind} or {@code happened}. An agent calling
 * {@code happened()} to find out what had gone on was never told that anything had gone wrong. The
 * consumer who reported the missing agent on {@code failed} got the day's actual answer out of
 * {@code docker logs} instead of out of the record, and that is the reason why.
 *
 * <p>So this asserts the closure rather than any one kind: everything written is readable, the
 * omissions from the one-line summary are the three that have a reason, and adding a write method
 * to the interface fails this test until somebody says which of the two it is.
 */
class EveryKindWrittenHasAReaderThatCanSeeItTest {

    /**
     * OMITTED FROM {@code happened} ON PURPOSE, EACH FOR A REASON THAT HAD TO BE STATED HERE.
     *
     * <p>{@code exchange} — a re-rendered conversation is not one line, and this summary is one
     * line per event; navigation sees them, which is where the largest rows belong.
     * <p>{@code thought} — the reasoning behind an answer whose {@code asked} row already carries
     * the answer, so including both doubles the summary for a single event.
     * <p>{@code priced} — cost accounting, which is for whoever pays and not for an agent deciding
     * what to do next.
     *
     * <p>Nothing else may be missing, and {@code failed} is missing from no reader at all.
     */
    private static final Set<String> NOT_IN_THE_SUMMARY = Set.of("exchange", "thought", "priced");

    /** Every abstract writer on the interface, which this test undertakes to exercise. */
    private static final Set<String> WRITERS = Set.of("asked", "applied", "tool", "thought",
            "built", "settled", "failed", "progress", "priced");

    @Test
    void everyWriterOnTheInterfaceIsExercisedByThisTest() {
        Set<String> declared = new TreeSet<>();
        for (Method m : Trace.class.getDeclaredMethods()) {
            if (m.getReturnType() == void.class && Modifier.isAbstract(m.getModifiers())) {
                declared.add(m.getName());
            }
        }

        assertEquals(new TreeSet<>(WRITERS), declared,
                "a writer was added to Trace and this test was not told, so whatever kind it "
                        + "produces has never been checked against a reader");
    }

    @Test
    void everythingWrittenIsFoundByNavigationAndSummarisedUnlessThereIsAReason(@TempDir Path dir)
            throws Exception {
        JsonlTrace trace = new JsonlTrace(dir.resolve("trace.jsonl"), dir.resolve("settled.jsonl"),
                "run-1");

        trace.asked("planner", "the prompt", "the answer");
        trace.applied("wall", "the patch");
        trace.tool("planner", "grep", "{\"pattern\":\"x\"}", "one match");
        trace.thought("planner", "stop", "the reasoning", "the content");
        trace.built("wall", new Trace.Outcome(false, false, "two tests failed"));
        trace.settled("run-1", "green", "the gate passed", true, true);
        trace.failed("planner", "run-1", new IllegalStateException("the endpoint went away"));
        trace.progress("run-1", "still going");
        trace.priced("run-1", "12", "one model call");
        trace.exchanged(new Trace.Exchange("out", "planner", 3, "sent", "got", "grep", "stop",
                10, 20, 30, ""));

        Set<String> written = new LinkedHashSet<>();
        for (String row : Files.readAllLines(dir.resolve("trace.jsonl"))) {
            written.add(Json.row(row).get("kind"));
        }
        assertEquals(WRITERS.size() + 1, written.size(),
                "every writer produced a row, plus exchange: " + written);

        Set<String> navigable = trace.traceSlice("", "", 0, 100).stream()
                .map(Trace.Event::kind).collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(new TreeSet<>(written), new TreeSet<>(navigable),
                "NAVIGATION MUST SEE EVERYTHING. A kind whole() does not name renders blank and "
                        + "scan() skips a blank, so it is absent rather than ugly: "
                        + missing(written, navigable));

        String summary = trace.happened("", "", 100);
        Set<String> absent = new TreeSet<>();
        for (String kind : written) {
            if (!summarised(summary, kind)) {
                absent.add(kind);
            }
        }
        assertEquals(new TreeSet<>(NOT_IN_THE_SUMMARY), absent,
                "happened() may omit only the three kinds with a stated reason. Everything else "
                        + "must appear, and a kind that appears here without a reason beside it in "
                        + "NOT_IN_THE_SUMMARY vanished by accident.\nThe summary was:\n" + summary);

        assertTrue(summary.contains("FAILED: IllegalStateException: the endpoint went away"),
                "and the failure is in it, named, which is the whole point: " + summary);
    }

    /** Whether a kind left any trace of itself in the one-line summary. */
    private static boolean summarised(String summary, String kind) {
        return switch (kind) {
            case "asked" -> summary.contains("answered:");
            case "applied" -> summary.contains("the patch");
            case "tool" -> summary.contains("grep(");
            case "built" -> summary.contains("[build wall]");
            case "settled" -> summary.contains("SETTLED");
            case "failed" -> summary.contains("FAILED:");
            case "progress" -> summary.contains("still going");
            case "thought" -> summary.contains("the reasoning");
            case "priced" -> summary.contains("one model call");
            case "exchange" -> summary.contains("\"sent\"") || summary.contains("out ");
            default -> false;
        };
    }

    private static String missing(Set<String> written, Set<String> seen) {
        List<String> lost = written.stream().filter(k -> !seen.contains(k)).toList();
        return lost.isEmpty() ? "nothing missing" : "no reader for " + lost;
    }
}
