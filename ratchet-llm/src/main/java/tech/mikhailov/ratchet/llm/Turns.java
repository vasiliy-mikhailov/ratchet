package tech.mikhailov.ratchet.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * EVERYTHING SAID, APPEND-ONLY, AND THE CONVERSATION DERIVED FROM IT.
 *
 * <p>{@link Asking} held a {@code List<Said>} and that list WAS the conversation, so there was
 * nothing to derive from and nothing to replace against. A caller shortening it had to shorten the
 * only copy, and a reader afterwards could not tell a conversation that had been compacted from one
 * that had been short.
 *
 * <p>THE INVERSION IS DSH'S AND SO IS THE VOCABULARY. There the append-only log is the source of
 * truth and the message history is DERIVED from it; a replacement is itself an APPEND carrying
 * {@code surfaceOp: replace(start, end)} and the sequence numbers it shadowed, and the shadowed
 * entries stay in the log. Nothing is destroyed; a second view is shortened. This is that idea at
 * the size a library can carry — no session, no persistence, no sequence numbers across processes.
 *
 * <p>TWO AUDIENCES, ONE LOG, SEPARATED BY WHICH METHOD YOU CALL. {@link #messages()} is what the
 * model sees and deliberately shadows what was replaced. {@link #spoken()} is the transcript, and it
 * does not: a landed replacement would otherwise erase conversation a reader has already seen. dsh
 * names that distinction {@code isAppendSurfaceEvent} and warns about it in the same words; ratchet
 * has had the same split all along between {@code JsonlTrace} and the conversation, and never named
 * it.
 */
public final class Turns {

    /**
     * One thing said, and how it entered the conversation.
     *
     * @param said     the message itself
     * @param shadowed the positions in the SURFACE this replaced, or an empty list for an append
     */
    public record Entry(Said said, List<Integer> shadowed) {

        public Entry {
            shadowed = shadowed == null ? List.of() : List.copyOf(shadowed);
        }

        /** Whether this entered at the tail rather than over something already there. */
        public boolean appended() {
            return shadowed.isEmpty();
        }
    }

    private final List<Entry> log = new ArrayList<>();

    /** Positions in {@link #log} that are currently visible, in the order the model sees them. */
    private final List<Integer> surface = new ArrayList<>();

    private int generation;

    /** Say something. It goes on the end of both the log and the conversation. */
    public void said(Said said) {
        log.add(new Entry(said, List.of()));
        surface.add(log.size() - 1);
    }

    /**
     * REPLACE THE CONVERSATION FROM {@code from} UP TO BUT NOT INCLUDING {@code to}, BY APPENDING.
     *
     * <p>The replacement lands at the position the range occupied and the range leaves the surface;
     * every entry involved stays in the log, and the new entry records which positions it shadowed.
     * That is what makes a compaction legible afterwards — a reader can see what the agent stopped
     * being able to see, and when.
     *
     * @throws IllegalArgumentException when the range is outside the conversation, reversed, or
     *                                  would cut a tool call away from its result
     */
    public void replace(int from, int to, Said with) {
        if (from < 0 || to > surface.size() || from >= to) {
            throw new IllegalArgumentException("[" + from + ", " + to + ") is not a range inside a "
                    + "conversation of " + surface.size());
        }
        List<Said> now = messages();
        if (!Between.balancedBefore(now, from) || !Between.balancedBefore(now, to)) {
            // THE EDGE CHECK IS NOT OPTIONAL AND NOT THE CALLER'S TO SKIP. An orphaned call is the
            // shape that poisons a conversation for every later turn, and one that reached a server
            // drove its tool-call parser into a loop that took every endpoint on that engine down
            // for three hours. A compactor that manufactures one is worse than no compactor, so
            // this refuses rather than trusting the range it was handed.
            throw new IllegalArgumentException("[" + from + ", " + to + ") cuts a tool call away "
                    + "from its result. Use Between.balancedAtOrBefore to find an edge where no "
                    + "unanswered call crosses.");
        }
        List<Integer> shadowed = new ArrayList<>(surface.subList(from, to));
        log.add(new Entry(with, shadowed));
        surface.subList(from, to).clear();
        surface.add(from, log.size() - 1);
        generation++;
    }

    /** What the model sees: the surface, with replaced ranges shadowed. */
    public List<Said> messages() {
        List<Said> derived = new ArrayList<>(surface.size());
        surface.forEach(at -> derived.add(log.get(at).said()));
        return List.copyOf(derived);
    }

    /**
     * THE TRANSCRIPT: everything said, in the order it was said, replacements included.
     *
     * <p>Not {@link #messages()}, and the difference is the point. A landed replacement shadows
     * conversation a reader has already seen, so deriving a human-facing account from the surface
     * would erase it. Whoever writes the record wants this one.
     */
    public List<Entry> spoken() {
        return List.copyOf(log);
    }

    /** How many replacements have landed. Unchanged by ordinary appends. */
    public int generation() {
        return generation;
    }

    /** How many things the model can currently see. */
    public int size() {
        return surface.size();
    }
}
