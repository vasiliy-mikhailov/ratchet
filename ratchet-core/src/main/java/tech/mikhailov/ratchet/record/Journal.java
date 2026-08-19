package tech.mikhailov.ratchet.record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * WHAT THIS RUN HAS ALREADY FINISHED, so a killed one does not begin again from nothing.
 *
 * <p>A sweep runs for a fortnight and the harness changes daily, so lanes get killed, and every
 * killed run started over. One lane had twenty hours in it. The answer is not to checkpoint the
 * process. Almost everything a run knows is cheap to re-derive, because the agents are stateless
 * (one system prompt and one task string, nothing carried between calls, so there is no
 * conversation to rebuild) and the workspace is durable (each stage commits as it lands, so what
 * was edited is on disk and the readers re-read it for free). Three things cannot be recovered once
 * the workspace has moved, and this file is for exactly those:
 *
 * <ul>
 *   <li>the baseline measurement, taken before anything moved, under conditions that no longer
 *       exist afterwards. Once the tree is edited it cannot be retaken, and a run without it cannot
 *       be judged at all.
 *   <li>the list of items the walk chose, which is an agent's decision rather than a fact about the
 *       subject, so re-deriving it would derive a different answer.
 *   <li>which model-driven stages already completed, so they are not paid for twice.
 * </ul>
 *
 * <p>APPEND ONLY, one JSON object per line, beside the trace it belongs to. A resume must never
 * rewrite history: the rows are the evidence that the work landed, and a writer that rewrote the
 * file could not be tailed by a second reader while the run is still going.
 *
 * <p>THE KEY IS THE PAIR (node, key) BECAUSE A WALK REPEATS THE NODE. A stage inside a walk
 * completes once for each item, so a journal keyed on the node alone would watch the first item
 * finish and skip the other nineteen.
 *
 * <p>WHAT IT DOES NOT HOLD. Budget spent is not journaled, because it is derivable from the rows
 * that are already here: see {@link #count}. Anything a resume can read back out of the workspace
 * belongs in the workspace.
 */
public final class Journal {

    /**
     * THE MARK OF A ROW THAT WAS WRITTEN WHOLE, and the reason a torn line is not corruption.
     *
     * <p>This file is written by a process that gets killed, so a half-written last line is the
     * NORMAL case rather than a fault. Skipping a line that will not parse is not enough on its
     * own: a row cut in the middle of an answer still parses, and a journal that accepted it would
     * replay half an answer as though the node had returned it. So every complete row ends with
     * this field and a row without it was still being written. It cannot be forged from inside a
     * value either: every quote in a value is escaped, so the sequence that closes a row can only
     * come from the writer.
     */
    private static final String END = "end";

    private static final String WHOLE = "row";

    /**
     * A SEPARATOR NEITHER HALF CAN CONTAIN, so one pair cannot be mistaken for another. Joined on
     * an ordinary character, node "a" with key "b c" and node "a b" with key "c" are the same pair,
     * and the second one then replays the first one's answer with nothing to say it did.
     */
    private static final String SEP = Character.toString(0);

    private final Path file;

    /** Where the tree is now, asked at the moment a row is written. Empty when nothing binds it. */
    private final Supplier<String> workspaceSha;

    /** (node, key) to what the node answered. Later rows win: append-only means the last is truth. */
    private final Map<String, String> answers = new LinkedHashMap<>();

    private final Map<String, String> facts = new LinkedHashMap<>();

    /** The tree the most recently completed node landed on. */
    private String tree = "";

    /** A journal whose rows name no tree, for a caller with nothing to bind a resume to. */
    public Journal(Path file) {
        this(file, () -> "");
    }

    /**
     * The usual one: rows carry the workspace they landed on, so a resume can tell whether the
     * checkout is still the one the journal was made against.
     */
    public Journal(Path file, Supplier<String> workspaceSha) {
        this.file = file;
        this.workspaceSha = workspaceSha == null ? () -> "" : workspaceSha;
        replay();
    }

    /**
     * NODE X FOR KEY K FINISHED, AND THIS IS WHAT IT SAID.
     *
     * <p>THE ORDER IS THE WHOLE SAFETY ARGUMENT, and the next person here will be tempted to write
     * the row first. Do not. A row is written AFTER the node returns, by which time the node has
     * already committed its edits to the workspace, so a row that is present implies the work
     * landed. A crash in the window between the edit and the row means the node runs again on the
     * resume, which is safe because the work is idempotent: doing what is already done is a no-op,
     * and a stage that is already satisfied costs one model call to agree.
     * Writing the row first inverts that. The crash then loses the edit while the journal swears it
     * happened, and nothing downstream can tell, which is the one failure this file must not have.
     */
    public synchronized void done(String node, String key, String answer, String workspaceSha) {
        String said = answer == null ? "" : answer;
        String sha = workspaceSha == null ? "" : workspaceSha;
        write(row("kind", "done", "node", node, "key", key, "answer", said, "sha", sha));
        answers.put(id(node, key), said);
        if (!sha.isBlank()) {
            tree = sha;
        }
    }

    /** The same, with the tree read from this journal's own supplier. What a resumable node calls. */
    public void done(String node, String key, String answer) {
        done(node, key, answer, workspaceSha.get());
    }

    /** What node X answered for key K, or empty when that pair has not completed. */
    public synchronized Optional<String> answered(String node, String key) {
        return Optional.ofNullable(answers.get(id(node, key)));
    }

    /**
     * SOMETHING THAT CANNOT BE MEASURED AGAIN.
     *
     * <p>The baseline is what forces this to exist. It is measured before anything moves, under
     * conditions that no longer exist afterwards, and the moment the workspace is edited it is gone
     * for good; a run that lost it can only settle as having no baseline, which is not a judgement
     * about the subject at all. The list the walk chose is the other one, and it is a different
     * kind of thing: not a measurement but a choice an agent made, which a second run would make
     * differently.
     */
    public synchronized void fact(String name, String value) {
        String said = value == null ? "" : value;
        write(row("kind", "fact", "name", name, "value", said));
        facts.put(name, said);
    }

    public synchronized Optional<String> fact(String name) {
        return Optional.ofNullable(facts.get(name));
    }

    /**
     * HOW MANY STEPS OF THIS NODE COMPLETED, which is how the budget is derived rather than kept.
     *
     * <p>A spent-so-far counter would be a second copy of a fact these rows already carry, and two
     * copies of one fact drift. Written before its row, a crash between the two buys turns nobody
     * ran; written after, the same crash loses the turn that was interrupted. Counting the rows has
     * neither failure, because there is only the one fact.
     *
     * <p>DISTINCT KEYS RATHER THAN ROWS. The count answers how many steps finished, and an
     * append-only file is allowed to carry the same pair twice.
     */
    public synchronized int count(String node) {
        String prefix = (node == null ? "" : node) + SEP;
        return (int) answers.keySet().stream().filter(k -> k.startsWith(prefix)).count();
    }

    /** The tree the most recently completed node landed on, empty when nothing has completed. */
    public synchronized Optional<String> tree() {
        return tree.isBlank() ? Optional.empty() : Optional.of(tree);
    }

    /**
     * WHETHER THIS JOURNAL BELONGS TO THAT CHECKOUT: THE CHECK, AND NOT THE POLICY.
     *
     * <p>A journal binds a resume to the tree it was made on. If the workspace is not where the
     * journal left it, replaying is worse than starting over: the stages that are skipped are
     * skipped against edits that are not there, and the run is then judged on a workspace nobody
     * built. What to do about that is the caller's, so this only answers the question.
     *
     * <p>True when nothing has completed yet, and true when no row named a tree at all. Neither is
     * evidence of a mismatch, and answering false there would refuse every fresh run.
     */
    public synchronized boolean standsOn(String workspaceSha) {
        return tree.isBlank() || tree.equals(workspaceSha);
    }

    private static String id(String node, String key) {
        return (node == null ? "" : node) + SEP + (key == null ? "" : key);
    }

    private static Map<String, String> row(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return m;
    }

    private void write(Map<String, String> fields) {
        StringBuilder b = new StringBuilder("{");
        // WHEN, on every row, for the reason the trace carries it too: half of what anyone reads a
        // record for is how long something took.
        b.append("\"at\":\"").append(System.currentTimeMillis()).append('"');
        fields.forEach((k, v) -> b.append(",\"").append(k).append("\":\"")
                .append(Settlement.escape(v)).append('"'));
        b.append(",\"").append(END).append("\":\"").append(WHOLE).append("\"}\n");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, b, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException cannotWrite) {
            // A journal that cannot be written must not end a run that is otherwise fine. It costs
            // the resume, which is what every run cost before this file existed, so say so and go on.
            System.err.println("journal: " + cannotWrite.getMessage());
        }
    }

    /**
     * THE FILE, READ BACK AS THE STATE IT RECORDS.
     *
     * <p>Bytes rather than lines, decoded leniently. A file cut in the middle of a character is
     * exactly what a killed process leaves behind, and {@code Files.readAllLines} throws on one,
     * which would turn the normal case into a journal nothing can read.
     */
    private void replay() {
        if (!Files.isReadable(file)) {
            return;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return;
        }
        for (String line : text.split("\n")) {
            accept(line);
        }
        heal(text);
    }

    /**
     * A TORN TAIL GETS ITS OWN LINE BACK.
     *
     * <p>A row is one write ending in a newline, so a process killed mid-write leaves a file whose
     * last line has no newline on it. Appending the next row straight onto that stump would splice
     * two rows into one line, and a splice can carry a valid end marker with somebody else's node
     * and key in front of it. Closing the stump when the file is opened costs one byte and keeps
     * the promise that a line is a row. The stump itself stays where it is: it is skipped by every
     * later read, and rewriting the file to tidy it away is the one thing an append-only record
     * must not do.
     */
    private void heal(String text) {
        if (text.isEmpty() || text.endsWith("\n")) {
            return;
        }
        try {
            Files.writeString(file, "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException cannotWrite) {
            System.err.println("journal: " + cannotWrite.getMessage());
        }
    }

    private void accept(String line) {
        String row = line.strip();
        if (row.length() < 2 || row.charAt(0) != '{' || row.charAt(row.length() - 1) != '}') {
            return;
        }
        Map<String, String> fields;
        try {
            fields = Json.row(row);
        } catch (RuntimeException torn) {
            return;
        }
        if (!WHOLE.equals(fields.get(END))) {
            return;
        }
        switch (fields.getOrDefault("kind", "")) {
            case "done" -> {
                answers.put(id(fields.getOrDefault("node", ""), fields.getOrDefault("key", "")),
                        fields.getOrDefault("answer", ""));
                String sha = fields.getOrDefault("sha", "");
                if (!sha.isBlank()) {
                    tree = sha;
                }
            }
            case "fact" -> facts.put(fields.getOrDefault("name", ""), fields.getOrDefault("value", ""));
            default -> {
            }
        }
    }
}
