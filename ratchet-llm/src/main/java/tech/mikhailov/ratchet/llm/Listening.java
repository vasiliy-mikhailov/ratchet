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
     * WHO IS SPEAKING NOW ARRIVES WITH THE QUESTION, and this class used to reconstruct it.
     *
     * <p>What stood here was a process-global {@code Map<String, String>} keyed by system prompt,
     * a {@code register(agent, prompt)} every consumer had to remember to call, and an
     * {@code agentOf} that scanned every registered prompt looking for the longest one contained in
     * the message. The javadoc defended it well and the defence was true: one client serves every
     * agent in a flow, a thread-local is a guess once the transport has its own thread, and the
     * system message was the one reliable thing that arrived.
     *
     * <p>IT WAS TRUE OF SOMEBODY ELSE'S REQUEST TYPE. This library defines {@link Ask} now, and
     * nothing prevented a third component — so 0.13.0 removed the reason for the registry and kept
     * the registry. ratchet#10 is the consumer who noticed, and the cost they measured is why this
     * is a deletion rather than a deprecation: 277,022 exchange rows in one sweep, each scanning
     * around twenty keys, one of which was a prompt with a 23 KB bill of materials spliced into it.
     *
     * <p>{@link Asking} already had the answer in a field and was already handing it to the tool
     * listener exactly. Two mechanisms answering one question, and only one of them could be wrong.
     */
    private final Trace trace;

    /** Agents whose system prompt this run has already written down once. */
    private final Set<String> promptRecorded = ConcurrentHashMap.newKeySet();

    /**
     * HOW MUCH OF EACH COLUMN SURVIVES ONTO DISK, chosen by whoever built the client.
     *
     * <p>Three numbers used to be written here as literals: 4,000 on the prompt and 900 twice. See
     * {@link Keeping} for what a consumer's corpus said about them and why they are not one number.
     */
    private final Keeping keeping;

    /** The shipped bounds, which is what every caller had before {@link Keeping} existed. */
    Listening(Trace trace) {
        this(trace, Keeping.shipped());
    }

    Listening(Trace trace, Keeping keeping) {
        this.trace = trace;
        this.keeping = keeping;
    }

    /**
     * WRITTEN WHEN IT IS SENT, not when the answer comes back.
     *
     * <p>Both halves used to be recorded at completion. A call that takes seventeen seconds then
     * filed its own prompt seventeen seconds late, AFTER the streamed reasoning it had caused, so
     * the record read backwards: the model thinking, and then what it had been asked.
     */
    void sending(Ask ask) {
        try {
            List<Said> messages = ask.messages();
            trace.exchanged(new Trace.Exchange("to", ask.from(), messages.size(),
                    outbound(ask.from(), messages), "", "", "", 0, 0, 0, ""));
        } catch (RuntimeException recordingMustNotBreakTheRun) {
            note(recordingMustNotBreakTheRun);
        }
    }

    void back(Ask ask, Reply reply, long ms) {
        try {
            List<Said> sent = ask.messages();
            String tools = reply.calls().stream().map(Called::name).distinct()
                    .reduce((a, b) -> a + "," + b).orElse("");
            trace.exchanged(new Trace.Exchange("back", ask.from(), sent.size(), "",
                    kept(Keeping.Column.ANSWER, reply.said()), tools, reply.ending().name(),
                    reply.spend().prompt(), reply.spend().completion(), ms, ""));
        } catch (RuntimeException recordingMustNotBreakTheRun) {
            // A LISTENER THAT THROWS TAKES THE CALL WITH IT. Nothing here is worth failing a run
            // for, so an unexpected shape is dropped rather than propagated.
            note(recordingMustNotBreakTheRun);
        }
    }

    void failed(Ask ask, Throwable cause, long ms) {
        try {
            List<Said> sent = ask.messages();
            trace.exchanged(new Trace.Exchange("back", ask.from(), sent.size(), "", "",
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
                            .append(kept(Keeping.Column.PROMPT, m.text())).append("\n\n");
                } else {
                    out.append("[system: ").append(agent).append("'s prompt, unchanged, ")
                            .append(m.text().length()).append(" chars]\n\n");
                }
                continue;
            }
            out.append('[').append(role(m)).append("]\n")
                    .append(kept(column(m), said(m))).append("\n\n");
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
        // THE TOOL THAT PRODUCED IT MATTERS AS MUCH AS THE TEXT: a result with no name reads as an
        // answer from nowhere, and by the third turn a request carries several of them. ratchet#9.
        if (m.role() == Said.Role.TOOL) {
            String named = m.answeringName();
            return named.isEmpty() ? m.text() : named + " -> " + m.text();
        }
        if (m.role() == Said.Role.ASSISTANT && m.text().isEmpty() && !m.calls().isEmpty()) {
            return m.calls().stream().map(c -> c.name() + c.arguments())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
        }
        return m.text();
    }

    /**
     * WHATEVER IS CUT SAYS HOW MUCH THERE WAS, which is the sentence whose absence hid this bound
     * for eight releases. A marker reading only {@code (truncated)} censors the distribution at the
     * clip: one consumer measured p90 = p99 = max = 916 on the answer column and had to reach for
     * token counts in another field to learn what the record was withholding. A total makes a wrong
     * bound visible from the corpus alone, with nothing re-run.
     *
     * <p>The form is the one this module already uses at {@link Asking}'s watcher.
     *
     * <p>NEWLINES SURVIVE. They were flattened to keep a trace line short, which turned a prompt
     * into an unreadable ribbon in the one view whose job is showing what was said. The JSON writer
     * escapes them; the page renders them.
     */
    private String kept(Keeping.Column column, String text) {
        String s = text == null ? "" : text.strip();
        int room = keeping.room(column, s);
        if (room < 0) {
            // Reported rather than clamped. A policy that answers a negative is broken and its
            // author needs to know; the catch around every call site turns this into a progress
            // note, so the run survives and the record says which column asked.
            throw new IllegalArgumentException(
                    "a keeping policy answered " + room + " for the " + column + " column");
        }
        return s.length() <= room ? s
                : s.substring(0, room) + "\n... (truncated, total " + s.length() + " chars)";
    }

    /**
     * WHICH COLUMN A MESSAGE IS, which is finer than the render/prompt split because a tool result
     * and an instruction land in the same place and are not the same kind of thing. One consumer's
     * tools span three orders of magnitude and only they know which is which.
     */
    private static Keeping.Column column(Said m) {
        return switch (m.role()) {
            case USER -> Keeping.Column.USER;
            case ASSISTANT -> Keeping.Column.ASSISTANT;
            case TOOL -> Keeping.Column.TOOL_RESULT;
            case SYSTEM -> Keeping.Column.PROMPT;
        };
    }
}
