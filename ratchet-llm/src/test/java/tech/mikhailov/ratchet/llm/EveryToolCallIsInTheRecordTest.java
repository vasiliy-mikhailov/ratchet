package tech.mikhailov.ratchet.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        String answered = run(Recording.at(one(request -> huge), calls, "agent:doer", 20_000));

        assertEquals(50_000, calls.results.get(0).length(), "the corpus wants everything");
        assertEquals(20_000 + "\n[truncated: 50000 chars total. Narrow the request if you need the rest.]"
                .length(), answered.length(), "the prompt does not");
        assertTrue(answered.endsWith("chars total. Narrow the request if you need the rest.]"),
                "and it says it was cut rather than pretending that was the answer");
    }

    @Test
    void aResultInsideTheBoundIsUntouched() {
        Calls calls = new Calls();

        String answered = run(Recording.at(one(request -> "the answer, verbatim"), calls,
                "agent:doer", 20_000));

        assertEquals("the answer, verbatim", answered);
        assertEquals("the answer, verbatim", calls.results.get(0));
        assertEquals("{\"path\":\"x\"}", calls.arguments.get(0), "with the arguments beside it");
    }

    @Test
    void aThrowIsAnEventRatherThanAnAbsence() {
        // The agent runtime catches this and hands the message to the model as the call's result,
        // so without the record the model is told something the trace never mentions.
        Calls calls = new Calls();
        var tools = Recording.at(one(request -> {
            throw new IllegalStateException("the tool blew up");
        }), calls, "agent:doer", 20_000);

        assertThrows(IllegalStateException.class, () -> run(tools));

        assertEquals(1, calls.results.size(), "the failed call is in the record");
        assertTrue(calls.results.get(0).contains("the tool blew up"), calls.results.get(0));
    }

    @Test
    void theDeclaredOrderSurvives() {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        for (String name : List.of("read", "grep", "edit")) {
            tools.put(spec(name), (request, memoryId) -> name);
        }

        var wrapped = Recording.at(tools, new Calls(), "agent:doer", 20_000);

        assertEquals(List.of("read", "grep", "edit"),
                wrapped.keySet().stream().map(ToolSpecification::name).toList(),
                "the order somebody wrote them down, not an accident of hashing");
    }

    private static String run(Map<ToolSpecification, ToolExecutor> tools) {
        var entry = tools.entrySet().iterator().next();
        return entry.getValue().execute(
                ToolExecutionRequest.builder().id("t").name(entry.getKey().name())
                        .arguments("{\"path\":\"x\"}").build(),
                "memory");
    }

    private static Map<ToolSpecification, ToolExecutor> one(
            java.util.function.Function<ToolExecutionRequest, String> answering) {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        tools.put(spec("read"), (request, memoryId) -> answering.apply(request));
        return tools;
    }

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder()
                .name(name)
                .description("a tool that exists so there is something to record")
                .parameters(JsonObjectSchema.builder().build())
                .build();
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

        public void thought(String f, String t, String c) {
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
