package tech.mikhailov.ratchet.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tech.mikhailov.ratchet.record.Trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHOLE INTO THE RECORD, BOUNDED BACK INTO THE PROMPT, and those are two different numbers.
 *
 * <p>The listener interface shortens what it reports, which is right for watching and wrong for a
 * corpus: the argument to an edit tool is the work the agent did. The class this replaces was
 * written twice; the second copy recorded neither the truncation nor the throw, and two units of
 * work were postponed with correct reasons while the trace recorded no postpone call at all.
 */
class EveryToolCallIsInTheRecordTest {

    @Test
    void theRecordGetsItWholeAndTheModelGetsItBounded() {
        String huge = "x".repeat(50_000);
        Calls calls = new Calls();

        String answered = run(Recording.at(one(call -> huge), calls, "agent:doer", 20_000));

        assertEquals(50_000, calls.results.get(0).length(), "the corpus wants everything");
        assertEquals(20_000 + "\n... (truncated, total 50000 chars) Narrow the request if you need "
                .length() + "the rest.".length(), answered.length(), "the prompt does not");
        assertTrue(answered.endsWith("Narrow the request if you need the rest."),
                "and it says it was cut rather than pretending that was the answer");
    }

    @Test
    void aResultInsideTheBoundIsUntouched() {
        Calls calls = new Calls();

        String answered = run(Recording.at(one(call -> "the answer, verbatim"), calls,
                "agent:doer", 20_000));

        assertEquals("the answer, verbatim", answered);
        assertEquals("the answer, verbatim", calls.results.get(0));
        assertEquals("{\"path\":\"x\"}", calls.arguments.get(0), "with the arguments beside it");
    }

    @Test
    void aThrowIsAnEventRatherThanAnAbsence() {
        // Asking catches this and hands the message to the model as the call's result, so without
        // the record the model is told something the trace never mentions.
        Calls calls = new Calls();
        var tools = Recording.at(one(call -> {
            throw new IllegalStateException("the tool blew up");
        }), calls, "agent:doer", 20_000);

        assertThrows(IllegalStateException.class, () -> run(tools));

        assertEquals(1, calls.results.size(), "the failed call is in the record");
        assertTrue(calls.results.get(0).contains("the tool blew up"), calls.results.get(0));
    }

    @Test
    void theDeclaredOrderSurvives() {
        // SIX NAMES, WHERE THIS WAS WRITTEN WITH THREE, AND THE THREE ARE WHY. Under the
        // specification type this replaces, a HashMap iterated read, grep, edit as grep, edit,
        // read, so the ordered map was the only thing making this pass. Tool is a record, and its
        // hash of those same three lands them in exactly the order they were declared: swapping
        // Recording's LinkedHashMap for a HashMap left the test green, which is a test that
        // asserts nothing. Six names put the difference back — hashed, they come out read, write,
        // grep, glob, bash, edit — and make a second coincidence one permutation in seven hundred.
        Map<Tool, Calling> tools = new LinkedHashMap<>();
        for (String name : List.of("read", "grep", "edit", "write", "bash", "glob")) {
            tools.put(spec(name), call -> name);
        }

        var wrapped = Recording.at(tools, new Calls(), "agent:doer", 20_000);

        assertEquals(List.of("read", "grep", "edit", "write", "bash", "glob"),
                wrapped.keySet().stream().map(Tool::name).toList(),
                "the order somebody wrote them down, not an accident of hashing");
    }

    /**
     * Calls the first wrapped tool exactly the way the loop does.
     *
     * <p>One argument now. The executor this replaces took a conversation id beside the call, and
     * this library configures no chat memory, so every caller passed a value the tool could not
     * use — here it was the string "memory", which is as meaningful as the null the runtime sent.
     */
    private static String run(Map<Tool, Calling> tools) {
        var entry = tools.entrySet().iterator().next();
        return entry.getValue().run(new Called("t", entry.getKey().name(), "{\"path\":\"x\"}"));
    }

    private static Map<Tool, Calling> one(Function<Called, String> answering) {
        Map<Tool, Calling> tools = new LinkedHashMap<>();
        tools.put(spec("read"), answering::apply);
        return tools;
    }

    private static Tool spec(String name) {
        return Tool.of(name, "a tool that exists so there is something to record");
    }

    /** A trace that keeps only what a tool call wrote. */
    private static final class Calls implements Trace {
        final List<String> arguments = new ArrayList<>();
        final List<String> results = new ArrayList<>();

        public void tool(String agent, String name, String args, String result) {
            arguments.add(args);
            results.add(result);
        }

        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void thought(String agent, String f, String t, String c) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String k, String s, String w, boolean before, boolean after) {
        }

        public void failed(String k, Throwable c) {
        }

        public void progress(String k, String n) {
        }

        public void priced(String k, String m, String i) {
        }
    }
}
