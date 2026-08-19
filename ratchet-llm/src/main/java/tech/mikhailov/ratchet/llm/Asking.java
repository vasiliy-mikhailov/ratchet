package tech.mikhailov.ratchet.llm;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.record.ToolWatching;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * ONE AGENT: A SYSTEM PROMPT, A CLOSED SET OF TOOLS, AND A QUESTION ASKED UNTIL IT IS ANSWERED.
 *
 * <p>THIS IS CONFIGURATION, NOT A LOOP. langchain4j already owns the tool loop, so all that is
 * written here is which model answers, what it is told before the question, which tools it may
 * call, how many rounds it gets and who is told about the calls. The class this replaces was the
 * same handful of builder calls behind a jar, and everything else that jar carried -- a todo list,
 * skills, a memory file, an orchestrator that delegates to sub-agents -- is what
 * {@link tech.mikhailov.ratchet.flow.Triad} and the flow around it were built to stand in for. A
 * batteries-included harness assumes a model clever enough to choose its own trajectory; the
 * structure in this library is what it substitutes for that assumption, so the parts that were
 * never used were never missing.
 *
 * <p>NOTHING SURVIVES BETWEEN CALLS. No chat memory is configured, so every {@link #run} is a fresh
 * two-message conversation: the system prompt, then the task. An agent asked twice in one run is
 * asked twice from nothing, and what it knows the second time is whatever its tools can tell it.
 * The service proxy is built once here rather than once per call, which changes nothing about that
 * -- with no memory there is nothing for a proxy to carry -- and stops repeating the reflective
 * scan behind it.
 *
 * <p>BOTH STRINGS ARE RENDERED AS PROMPT TEMPLATES, which is langchain4j's behaviour rather than a
 * choice made here, and is unchanged from what ran before. A {@code {{name}}} in a system prompt or
 * in a task is either substituted from {@code current_date}, {@code current_time} and
 * {@code current_date_time} or is a fatal IllegalArgumentException naming the variable it could not
 * resolve. No prompt in this program contains one.
 *
 * <p>A TOOL THAT FAILS AND A TOOL THAT DOES NOT EXIST ARE NOT THE SAME EVENT, and the first draft
 * of this paragraph said they were. What an executor throws is caught by langchain4j and handed to
 * the model as that call's result, so the conversation carries on rather than dying. Measured, and
 * pinned by {@code aToolThatThrowsIsAnsweredToTheModelRatherThanEndingTheRun}. Answering with a
 * written error string instead of throwing is therefore a courtesy to the model rather than the
 * thing keeping the run alive, and it is still the better habit, because a sentence composed for a
 * reader beats whatever a stack trace's first line happens to say.
 *
 * <p>A TOOL NAME THE MODEL INVENTS IS THE OTHER PATH. langchain4j's hallucination strategy raises,
 * that raise leaves {@link #run}, and the ask is lost with it, which is why a tool a phase needs is
 * written rather than assumed. Pinned by
 * {@code aToolNameTheModelInventedIsNotAnsweredTheSameWayAsAToolThatFailed}.
 *
 * <p>TOOLS ARE EXECUTED SEQUENTIALLY, in the order the model asked for them, on the calling thread:
 * no executor is configured, so nothing here runs two tools at once even when one assistant turn
 * asks for a dozen.
 */
public final class Asking implements Agent {

    /**
     * TWENTY-FIVE ROUNDS, AND THE TWENTY-SIXTH THROWS. THE NUMBER IS PRESERVED ON PURPOSE.
     *
     * <p>It was not invented here and it is not a tidy default. The jar this class replaces passed
     * exactly 25 to langchain4j, and langchain4j neither truncates at the bound nor stops quietly:
     * at the top of every pass it decrements a counter and, at zero, throws
     * {@code RuntimeException("Something is wrong, exceeded 25 sequential tool executions")}. The
     * check runs BEFORE the test for whether the response even asked for a tool, so at most 25
     * model responses are processed and an answer is only returned if it arrives as the
     * twenty-fifth or earlier. When all 25 rounds called tools, those tools really ran and their
     * edits to the workspace are real; it is the twenty-sixth response that is fetched and thrown
     * away.
     *
     * <p>IT BINDS, AND IT BINDS OFTEN. That exact message appears 60,173 times in one corpus's own
     * record, and in one results tree 59 of 208 runs hit it at least once. Both callers catch the
     * RuntimeException, record "unreachable" against the agent, and re-ask the whole question
     * through the thinking-off retry model, so every firing costs a second conversation. The bound
     * counts ROUNDS and not calls, despite its name: one assistant message asking for five tools
     * costs one, and the busiest recorded conversation fitted 465 calls inside this budget by
     * batching.
     *
     * <p>So raising it is a change to what more than a quarter of runs do and it would silence a
     * row the record currently carries. That is a decision to take deliberately and measure, not a
     * number to tidy away while removing a dependency. langchain4j's own default is 100.
     */
    private static final int MAX_ROUNDS = 25;

    /** What a listener is shown of an argument or a result. It is for watching, not for the record. */
    private static final int MAX_WATCHED = 8_000;

    /**
     * The one method langchain4j builds a service around.
     *
     * <p>Nested rather than declared anywhere a caller could see it, because nothing outside this
     * class ever holds one: the interface exists so that the proxy has a signature, and the
     * signature is the whole of it.
     */
    interface Turn {
        String reply(@UserMessage String message);
    }

    private final Turn turn;

    /**
     * @param model        who answers; a critic and a producer are given different ones
     * @param systemPrompt what the model is told before the question, in full
     * @param tools        every tool this agent may call, and no others
     * @param label        who is calling, for the listener, e.g. {@code agent:survey-doer}
     * @param listener     told about each call, or null when nobody is watching
     */
    public Asking(ChatModel model, String systemPrompt,
                  Map<ToolSpecification, ToolExecutor> tools, String label,
                  ToolWatching listener) {
        // Effectively final, because the provider below closes over it. The provider ignores the
        // memory id it is handed: every conversation on this agent is told the same thing.
        final String prompt = systemPrompt;
        Map<ToolSpecification, ToolExecutor> wired = watched(tools, label, listener);
        var builder = AiServices.builder(Turn.class)
                .chatModel(model)
                .systemMessageProvider(memoryId -> prompt)
                .maxSequentialToolsInvocations(MAX_ROUNDS);
        // AN EMPTY SET IS NOT THE SAME AS NO TOOLS. An agent with nothing to call is a legitimate
        // shape and langchain4j will not accept an empty map, so the call is skipped rather than
        // made with nothing in it.
        if (!wired.isEmpty()) {
            builder.tools(wired);
        }
        this.turn = builder.build();
    }

    /**
     * The final assistant text, WHICH CAN BE NULL.
     *
     * <p>A model that ends its turn with tool calls and no content answers nothing at all, and that
     * is an empty judgement rather than a failure: {@link Insisting} is what reads it as one and
     * asks again. Throws whatever the model call or the round bound throws, which callers catch and
     * record rather than let end the run.
     */
    @Override
    public String run(String task) {
        return turn.reply(task);
    }

    /**
     * The same tools, each reporting itself to whoever is watching.
     *
     * <p>THE DECLARED ORDER IS KEPT. What this replaces copied the map into an unordered one on the
     * way in, so the order tools were advertised in was an accident of hashing; here it is the
     * order the caller declared them, which is the order a reader of that declaration expects.
     *
     * <p>Note that langchain4j keys executors by NAME while keeping the specifications as a list,
     * so two tools sharing a name collide silently, last writer winning, with both still advertised
     * to the model. Nothing here can catch that; the guard is the count assertion where the sets
     * are assembled.
     */
    private static Map<ToolSpecification, ToolExecutor> watched(
            Map<ToolSpecification, ToolExecutor> tools, String label, ToolWatching listener) {
        Map<ToolSpecification, ToolExecutor> wired = new LinkedHashMap<>();
        if (tools != null) {
            wired.putAll(tools);
        }
        if (listener == null || wired.isEmpty()) {
            return wired;
        }
        String context = label == null || label.isBlank() ? "agent" : label;
        Map<ToolSpecification, ToolExecutor> out = new LinkedHashMap<>();
        wired.forEach((spec, executor) -> out.put(spec, (request, memoryId) -> {
            String result = executor.execute(request, memoryId);
            // The listener is told "" for a null result; the model is told exactly what the
            // executor returned, null included, because that is what it returned.
            listener.onToolInvocation(context, asked(request, spec), memoryId,
                    shortened(request == null ? "" : request.arguments()),
                    shortened(result == null ? "" : result));
            return result;
        }));
        return out;
    }

    /** What the model called it, falling back to what it is actually called. */
    private static String asked(ToolExecutionRequest request, ToolSpecification spec) {
        return request != null && request.name() != null && !request.name().isBlank()
                ? request.name()
                : spec.name();
    }

    private static String shortened(String text) {
        return text.length() <= MAX_WATCHED
                ? text
                : text.substring(0, MAX_WATCHED) + "... (truncated, total " + text.length()
                        + " chars)";
    }
}
