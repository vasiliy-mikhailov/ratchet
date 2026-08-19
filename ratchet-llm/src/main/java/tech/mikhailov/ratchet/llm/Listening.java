package tech.mikhailov.ratchet.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tech.mikhailov.ratchet.record.Trace;

/**
 * EVERY EXCHANGE WITH THE MODEL, RECORDED WHERE IT HAPPENS.
 *
 * <p>The trace was assembled by the harness calling {@code trace.asked(...)} at the points it
 * remembered to. That is a curated record: it holds what somebody decided was worth keeping, and it
 * is silent about everything else. Three things one corpus needed were missing from it for exactly
 * that reason. Token counts, so the thinking budget could be checked against what the server
 * actually spent rather than against a character count. Which agent produced a piece of reasoning,
 * since {@code thought} events carry none and 737 of them in one sweep attributed to nobody. And
 * every call that failed before the harness got as far as recording an answer.
 *
 * <p>A listener sits under all of that. langchain4j hands it the request as sent and the response
 * as received, including the calls that error, so the record becomes what HAPPENED rather than what
 * was saved.
 *
 * <p>SUMMARISED, NOT DUMPED. Each request carries the whole conversation so far, and this corpus
 * has already measured a prompt growing monotonically to 428K tokens; writing every request in full
 * would be that same growth on disk, squared over a sweep. What is kept is the shape: how many
 * messages went, the tail of what was new, what came back, which tools were asked for, why the
 * generation stopped, what it cost.
 */
public final class Listening implements ChatModelListener {

    /**
     * WHICH AGENT IS SPEAKING, WITHOUT A THREAD-LOCAL.
     *
     * <p>Two models serve every agent in the flow, so the listener cannot be told the name when it
     * is built, and the streaming path makes a thread-local a guess rather than a fact. What is
     * reliable is the system message: every agent's prompt is distinct, and it travels with the
     * request. Registering them at definition time turns identity into a lookup.
     *
     * <p>REGISTRATION IS EXPLICIT, AND IT IS THE ONE THING A CONSUMER MUST NOT FORGET. {@code
     * Asking} could register itself, and then nobody could get this wrong; it does not, because it
     * is built with a LABEL and a consumer registers under a NAME, and the two are not the same
     * string. Auto-registering would silently rewrite the agent column of every exchange row a
     * corpus already holds. Call this once per agent, or every exchange is attributed to nobody:
     * 737 of them in one sweep were.
     */
    private static final Map<String, String> BY_PROMPT = new ConcurrentHashMap<>();

    public static void register(String agent, String systemPrompt) {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            BY_PROMPT.put(systemPrompt.strip(), agent);
        }
    }

    /** The longest registered prompt this system message carries; longest, so a prefix cannot win. */
    static String agentOf(String systemMessage) {
        if (systemMessage == null) {
            return "";
        }
        String best = "";
        int longest = 0;
        for (Map.Entry<String, String> e : BY_PROMPT.entrySet()) {
            if (e.getKey().length() > longest && systemMessage.contains(e.getKey())) {
                best = e.getValue();
                longest = e.getKey().length();
            }
        }
        return best;
    }

    private final Trace trace;

    Listening(Trace trace) {
        this.trace = trace;
    }

    private static final String STARTED = "ratchet.started";

    /**
     * WRITTEN WHEN IT IS SENT, not when the answer comes back.
     *
     * <p>Both halves used to be recorded from onResponse, which stamps them at completion. A call
     * that takes seventeen seconds then filed its own prompt seventeen seconds late, AFTER the
     * streamed reasoning it had caused, so the record read backwards: the model thinking, and then
     * what it had been asked. The listener is handed the request before the call goes out; that is
     * where the request belongs.
     */
    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ctx.attributes().put(STARTED, System.currentTimeMillis());
        try {
            List<ChatMessage> sent = ctx.chatRequest().messages();
            String agent = agentOf(system(sent));
            trace.exchanged(new Trace.Exchange("to", agent, sent.size(),
                    outbound(agent, sent), "", "", "", 0, 0, 0, ""));
        } catch (RuntimeException recordingMustNotBreakTheRun) {
            trace.progress("", "listener: " + recordingMustNotBreakTheRun);
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        try {
            List<ChatMessage> sent = ctx.chatRequest().messages();
            var response = ctx.chatResponse();
            var meta = response == null ? null : response.metadata();
            var usage = meta == null ? null : meta.tokenUsage();
            var ai = response == null ? null : response.aiMessage();
            String tools = ai == null || !ai.hasToolExecutionRequests() ? ""
                    : ai.toolExecutionRequests().stream()
                            .map(t -> t.name()).distinct().reduce((a, b) -> a + "," + b).orElse("");
            trace.exchanged(new Trace.Exchange(
                    "back",
                    agentOf(system(sent)),
                    sent.size(),
                    "",
                    tail(ai == null || ai.text() == null ? "" : ai.text()),
                    tools,
                    meta == null || meta.finishReason() == null ? "" : meta.finishReason().name(),
                    usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                    usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount(),
                    since(ctx.attributes()),
                    ""));
        } catch (RuntimeException recordingMustNotBreakTheRun) {
            // A LISTENER THAT THROWS TAKES THE CALL WITH IT. Nothing here is worth failing a run
            // for, so an unexpected shape is dropped rather than propagated.
            trace.progress("", "listener: " + recordingMustNotBreakTheRun);
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        try {
            List<ChatMessage> sent = ctx.chatRequest() == null ? List.of()
                    : ctx.chatRequest().messages();
            Throwable cause = ctx.error();
            trace.exchanged(new Trace.Exchange(
                    "back", agentOf(system(sent)), sent.size(), "", "", "", "ERROR", 0, 0,
                    since(ctx.attributes()),
                    cause == null ? "unknown" : cause.getClass().getSimpleName()
                            + ": " + String.valueOf(cause.getMessage())));
        } catch (RuntimeException ignored) {
            // As above: the error path is the last place to add a second failure.
        }
    }

    private static long since(Map<Object, Object> attributes) {
        Object started = attributes.get(STARTED);
        return started instanceof Long ms ? System.currentTimeMillis() - ms : 0;
    }

    private static String system(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage s) {
                return s.text();
            }
        }
        return "";
    }

    /** Agents whose system prompt this run has already written down once. */
    private final java.util.Set<String> promptRecorded = java.util.concurrent.ConcurrentHashMap
            .newKeySet();

    /**
     * WHAT ACTUALLY WENT, WHICH INCLUDES THE PROMPT.
     *
     * <p>This used to be the LAST message only. On a first call that is {@code [system, user]}, so
     * the record showed the task and dropped the instruction governing the agent -- the half that
     * decides what it does. "2 message(s)" was true and told a reader nothing about the one it
     * could not see.
     *
     * <p>THE SYSTEM PROMPT ONCE PER AGENT, then named rather than repeated. It is identical on
     * every call of that agent and runs to thousands of characters, so writing it each time would
     * put the same paragraphs on disk a hundred times per run. Once is what a reader needs, and it
     * is the prompt IN FORCE, so an edited one shows as edited rather than as whatever the code
     * ships.
     */
    private String outbound(String agent, List<ChatMessage> messages) {
        StringBuilder out = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage s) {
                String key = agent + "|" + s.text().length();
                if (promptRecorded.add(key)) {
                    out.append("[system: ").append(agent.isBlank() ? "unrecognised" : agent)
                            .append(", ").append(s.text().length()).append(" chars]\n")
                            .append(clip(s.text(), 4000)).append("\n\n");
                } else {
                    out.append("[system: ").append(agent).append("'s prompt, unchanged, ")
                            .append(s.text().length()).append(" chars]\n\n");
                }
                continue;
            }
            out.append('[').append(role(m)).append("]\n").append(clip(text(m), 900))
                    .append("\n\n");
        }
        return out.toString().strip();
    }

    private static String role(ChatMessage m) {
        if (m instanceof dev.langchain4j.data.message.UserMessage) {
            return "user";
        }
        if (m instanceof dev.langchain4j.data.message.AiMessage) {
            return "assistant";
        }
        if (m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage) {
            return "tool result";
        }
        return m.getClass().getSimpleName();
    }

    private static String clip(String text, int limit) {
        String s = text == null ? "" : text.strip();
        return s.length() <= limit ? s : s.substring(0, limit) + "\n... (truncated)";
    }

    private static String text(ChatMessage m) {
        return last(List.of(m));
    }

    /**
     * THE TEXT OF THE LAST MESSAGE, not its toString.
     *
     * <p>The record showed "UserMessage { name = null, contents = [TextContent { text = ..." on the
     * one view whose job is showing what was said. The wrapper is Java's, not the model's; the
     * model saw the text.
     */
    private static String last(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        ChatMessage m = messages.get(messages.size() - 1);
        if (m instanceof dev.langchain4j.data.message.UserMessage u) {
            return u.singleText();
        }
        if (m instanceof dev.langchain4j.data.message.AiMessage a) {
            return a.text() == null ? String.valueOf(m) : a.text();
        }
        if (m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage r) {
            // The tool that produced it matters as much as the text: a result with no name reads
            // as an answer from nowhere.
            return r.toolName() + " -> " + r.text();
        }
        if (m instanceof SystemMessage s) {
            return s.text();
        }
        return String.valueOf(m);
    }

    /**
     * Enough to recognise, not enough to reproduce the conversation on disk.
     *
     * <p>NEWLINES SURVIVE. They were flattened to keep a trace line short, which turned a prompt
     * into an unreadable ribbon in the one view whose job is showing what was said. The JSON writer
     * escapes them; the page renders them.
     */
    private static String tail(String text) {
        String flat = text == null ? "" : text.strip();
        return flat.length() <= 900 ? flat : flat.substring(0, 900) + "\n... (truncated)";
    }
}
