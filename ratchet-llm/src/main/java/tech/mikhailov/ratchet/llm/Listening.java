package tech.mikhailov.ratchet.llm;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import tech.mikhailov.ratchet.record.Trace;

/**
 * EVERY EXCHANGE WITH THE MODEL, RECORDED WHERE IT HAPPENS.
 *
 * <p>The trace used to be assembled by the harness calling {@code trace.asked(...)} at the points
 * it remembered to. That is a curated record: it holds what somebody decided was worth keeping and
 * is silent about everything else. Three things one corpus needed were missing for exactly that
 * reason. Token counts, so the thinking budget could be checked against what the server actually
 * spent rather than against a character count. Which agent produced a piece of reasoning, since
 * {@code thought} events carry none and 737 of them in one sweep attributed to nobody. And every
 * call that failed before the harness got as far as recording an answer.
 *
 * <p>This sat under all of that as a listener the client called. It is now called by {@link Wire}
 * directly, which changes nothing about what is written and removes the one thing that was
 * genuinely awkward: a listener interface whose contexts were the only route to the token counts.
 *
 * <p>SUMMARISED, NOT DUMPED. Each request carries the whole conversation so far, and this corpus
 * has already measured a prompt growing monotonically to 428K tokens; writing every request in full
 * would be that same growth on disk, squared over a sweep. What is kept is the shape: how many
 * messages went, the tail of what was new, what came back, which tools were asked for, why the
 * generation stopped, what it cost.
 */
public final class Listening {

    /**
     * WHICH AGENT IS SPEAKING, WITHOUT A THREAD-LOCAL AND WITHOUT A FIELD ON THE CLIENT.
     *
     * <p>ONE CLIENT SERVES EVERY AGENT IN THE FLOW — eight of them in the sample pipeline against a
     * single producer — so the client cannot be told the name when it is built, and doing it that
     * way would reproduce the exact bug this lookup exists to fix rather than remove it. A
     * thread-local is a guess once the transport has its own thread.
     *
     * <p>What is reliable is the system message: every agent's prompt is distinct and it travels
     * with the request. Registering them at definition time turns identity into a lookup.
     *
     * <p>REGISTRATION IS EXPLICIT, AND IT IS THE ONE THING A CONSUMER MUST NOT FORGET. {@link Asking}
     * could register itself, and then nobody could get this wrong; it does not, because it is built
     * with a LABEL and a consumer registers under a NAME, and the two are not the same string.
     * Auto-registering would silently rewrite the agent column of every exchange row a corpus
     * already holds. Call this once per agent, or every exchange is attributed to nobody: 737 of
     * them in one sweep were.
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

    /** Agents whose system prompt this run has already written down once. */
    private final Set<String> promptRecorded = ConcurrentHashMap.newKeySet();

    Listening(Trace trace) {
        this.trace = trace;
    }

    /**
     * WRITTEN WHEN IT IS SENT, not when the answer comes back.
     *
     * <p>Both halves used to be recorded at completion. A call that takes seventeen seconds then
     * filed its own prompt seventeen seconds late, AFTER the streamed reasoning it had caused, so
     * the record read backwards: the model thinking, and then what it had been asked.
     */
    void sending(List<Said> messages) {
        try {
            String agent = agentOf(system(messages));
            trace.exchanged(new Trace.Exchange("to", agent, messages.size(),
                    outbound(agent, messages), "", "", "", 0, 0, 0, ""));
        } catch (RuntimeException recordingMustNotBreakTheRun) {
            note(recordingMustNotBreakTheRun);
        }
    }

    void back(List<Said> sent, Reply reply, long ms) {
        try {
            String tools = reply.calls().stream().map(Called::name).distinct()
                    .reduce((a, b) -> a + "," + b).orElse("");
            trace.exchanged(new Trace.Exchange("back", agentOf(system(sent)), sent.size(), "",
                    tail(reply.said()), tools, reply.ending().name(),
                    reply.spend().prompt(), reply.spend().completion(), ms, ""));
        } catch (RuntimeException recordingMustNotBreakTheRun) {
            // A LISTENER THAT THROWS TAKES THE CALL WITH IT. Nothing here is worth failing a run
            // for, so an unexpected shape is dropped rather than propagated.
            note(recordingMustNotBreakTheRun);
        }
    }

    void failed(List<Said> sent, Throwable cause, long ms) {
        try {
            trace.exchanged(new Trace.Exchange("back", agentOf(system(sent)), sent.size(), "", "",
                    "", "ERROR", 0, 0, ms, cause == null ? "unknown"
                            : cause.getClass().getSimpleName() + ": " + cause.getMessage()));
        } catch (RuntimeException ignored) {
            // As above: the error path is the last place to add a second failure.
        }
    }

    private void note(RuntimeException failed) {
        try {
            trace.progress("", "listener: " + failed);
        } catch (RuntimeException nothingLeftToTry) {
            // Deliberately empty.
        }
    }

    private static String system(List<Said> messages) {
        for (Said m : messages) {
            if (m.role() == Said.Role.SYSTEM) {
                return m.text();
            }
        }
        return "";
    }

    /**
     * WHAT ACTUALLY WENT, WHICH INCLUDES THE PROMPT.
     *
     * <p>This used to be the LAST message only. On a first call that is {@code [system, user]}, so
     * the record showed the task and dropped the instruction governing the agent — the half that
     * decides what it does. "2 message(s)" was true and told a reader nothing about the one it
     * could not see.
     *
     * <p>THE SYSTEM PROMPT ONCE PER AGENT, then named rather than repeated. It is identical on every
     * call of that agent and runs to thousands of characters, so writing it each time would put the
     * same paragraphs on disk a hundred times per run. Once is what a reader needs, and it is the
     * prompt IN FORCE, so an edited one shows as edited rather than as whatever the code ships.
     */
    private String outbound(String agent, List<Said> messages) {
        StringBuilder out = new StringBuilder();
        for (Said m : messages) {
            if (m.role() == Said.Role.SYSTEM) {
                String key = agent + "|" + m.text().length();
                if (promptRecorded.add(key)) {
                    out.append("[system: ").append(agent.isBlank() ? "unrecognised" : agent)
                            .append(", ").append(m.text().length()).append(" chars]\n")
                            .append(clip(m.text(), 4000)).append("\n\n");
                } else {
                    out.append("[system: ").append(agent).append("'s prompt, unchanged, ")
                            .append(m.text().length()).append(" chars]\n\n");
                }
                continue;
            }
            out.append('[').append(role(m)).append("]\n").append(clip(said(m), 900)).append("\n\n");
        }
        return out.toString().strip();
    }

    private static String role(Said m) {
        return switch (m.role()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool result";
            case SYSTEM -> "system";
        };
    }

    /**
     * THE TEXT, not a toString.
     *
     * <p>The record showed {@code UserMessage { name = null, contents = [TextContent { text = ...}}
     * on the one view whose job is showing what was said. That wrapper was Java's, not the model's;
     * the model saw the text. Here there is no wrapper to leak, and an assistant turn that is
     * nothing but tool calls says so rather than showing an empty line.
     */
    private static String said(Said m) {
        if (m.role() == Said.Role.ASSISTANT && m.text().isEmpty() && !m.calls().isEmpty()) {
            return m.calls().stream().map(c -> c.name() + c.arguments())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
        }
        return m.text();
    }

    private static String clip(String text, int limit) {
        String s = text == null ? "" : text.strip();
        return s.length() <= limit ? s : s.substring(0, limit) + "\n... (truncated)";
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
