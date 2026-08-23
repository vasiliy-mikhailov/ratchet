package tech.mikhailov.ratchet.llm;

import java.util.HashMap;
import java.util.Map;

import tech.mikhailov.ratchet.record.Trace;

/**
 * WHAT THE MODEL WAS THINKING, KEPT — AND THE CYCLE IT CANNOT LEAVE, CAUGHT.
 *
 * <p>This class used to be a transport interceptor: it wrapped the client's own HTTP builder and
 * its SSE parser in order to see events the client was already parsing and then discarding, because
 * the server writes {@code delta.reasoning} and the client read {@code reasoning_content}. Eighty-
 * four calls produced eighty-four blanks before that was noticed, and every trace written after the
 * switch to streaming had zero thoughts while every earlier one had them.
 *
 * <p>THE INTERCEPTOR IS GONE AND THE DETECTOR IS WHY THIS FILE REMAINS. {@link Wire} reads whatever
 * field the server sends and hands the deltas here, so recovering the reasoning is no longer a
 * problem anybody has. What is left is the part that was never a workaround: the reasoning arrives
 * one token at a time, and a model whose reasoning has begun repeating itself is burning a budget
 * it will not come back from.
 *
 * <p>Accumulated and emitted ONCE, because a trace of ten thousand one-token rows is not a record
 * of anything.
 */
public final class Reasoning {

    /**
     * HOW MANY REPEATS OF ONE SUBSTANTIAL LINE COUNT AS A CYCLE RATHER THAN A HABIT.
     *
     * <p>Six, measured: catches 66 of 68 runaways in one corpus at a median 27% of the reasoning,
     * with 4 false positives in 824 healthy turns.
     */
    private static final int REPEATS = 6;
    private static final int SUBSTANTIAL = 60;

    /**
     * Thrown when the reasoning has begun repeating itself.
     *
     * <p>It leaves {@link Wire}'s read loop deliberately, so the body is closed and the server
     * stops generating. Swallowing it would save the trace and keep paying the bill: greedy
     * decoding cannot leave a cycle it has entered, and one experiment restarted 14 trapped
     * generations with 2,500 more tokens each — none escaped.
     */
    public static final class LoopDetected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        LoopDetected(String line, int times) {
            super("reasoning repeated a line " + times + " times: "
                    + line.substring(0, Math.min(90, line.length())));
        }
    }

    private final Trace trace;
    private final StringBuilder thinking = new StringBuilder();
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private final Map<String, Integer> seen = new HashMap<>();
    private String finish = "";

    /**
     * FIRE ONCE PER RESPONSE.
     *
     * <p>Throwing out of the old listener did not reliably cancel the stream: the client kept
     * delivering events, every later line re-tripped the already-satisfied counter, and one genuine
     * detection became 971 rows for a single run — 9,916 across one corpus against 776 normal
     * finishes. The detection was right; repeating it was not.
     */
    private boolean detected;

    /** One thought row per response, whatever order the end and a detection arrive in. */
    private boolean written;

    public Reasoning(Trace trace) {
        this.trace = trace;
    }

    /** More reasoning. May throw {@link LoopDetected}, which ends the stream on purpose. */
    public void reasoned(String delta) {
        if (delta == null || delta.isEmpty() || detected) {
            return;
        }
        thinking.append(delta);
        pending.append(delta);
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            String line = pending.substring(0, newline);
            pending.delete(0, newline + 1);
            String flattened = line.replaceAll("[\\s\\p{Punct}]+", " ").strip().toLowerCase();
            if (flattened.length() < SUBSTANTIAL) {
                continue;
            }
            int times = seen.merge(flattened, 1, Integer::sum);
            if (times >= REPEATS) {
                detected = true;
                finish = "loop";
                flush();
                throw new LoopDetected(line, times);
            }
        }
    }

    /** More answer. Kept only so the thought row can show what the thinking was for. */
    public void said(String delta) {
        if (delta != null && !detected) {
            content.append(delta);
        }
    }

    /** Why the generation stopped, as the server said it. */
    public void ended(String finishReason) {
        if (finishReason != null && !finishReason.isBlank() && !detected) {
            finish = finishReason;
        }
    }

    /**
     * Write the row, at most once.
     *
     * <p>Recorded whenever there is anything to say about how the answer ended, even with no
     * reasoning captured: only 75 of 182 empties were visible as {@code length} before, and an empty
     * with no row at all is an empty nobody can diagnose.
     */
    public void flush() {
        if (written || trace == null) {
            return;
        }
        written = true;
        try {
            if (thinking.length() == 0 && content.length() == 0 && !finish.isBlank()) {
                trace.thought(finish, "", "");
                return;
            }
            if (thinking.length() > 0) {
                trace.thought(finish, thinking.toString(), content.toString());
            }
        } catch (RuntimeException unrecordable) {
            // A trace that cannot be written must never be why a run fails.
        }
    }
}
