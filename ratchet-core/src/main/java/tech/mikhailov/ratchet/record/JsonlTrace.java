package tech.mikhailov.ratchet.record;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The trace as one append-only file per run, plus the settlements file beside it.
 *
 * <p>Two files, because they answer different questions. {@code trace.jsonl} is everything, for
 * analysis and for prompt tuning. {@code settlements.jsonl} is the last word per run, for a reader
 * who wants to know what happened rather than how.
 */
public final class JsonlTrace implements Trace, ToolWatching {

    private final Path trace;
    private final Path settlements;
    private final String key;

    /**
     * WHICH PIPELINE THIS ROW CAME FROM, supplied rather than worked out.
     *
     * <p>It returns the JSON fields naming the pipeline, ready to append and WITHOUT a leading
     * comma. It used to be computed here, by splitting the run key on a separator this class had no
     * business knowing about and handing the halves to a fingerprint whose two parts were named
     * after one pipeline's two kinds of input. What names a pipeline is the pipeline's business;
     * writing the row is this class's. So the pipeline hands over the text.
     */
    private final Supplier<String> provenance;

    /**
     * The phrases that outrank everything else in {@link #happened}, and the ones just below them.
     *
     * <p>Empty by default, which leaves ranking to the kind of event alone. That fallback is
     * structural and true of any pipeline; the phrases are not, because what counts as a verdict or
     * an objection is written in the caller's own prompts.
     */
    private final List<String> decisive;

    private final List<String> disputed;

    /**
     * COMPUTED ONCE PER LANE, because it is asked for on every progress write.
     *
     * <p>A fingerprint typically builds every agent for the run and hashes what each is handed,
     * which is the right answer and far too much work to repeat per event. A lane keeps the code it
     * started with, so the value it would compute later is the value it computed first.
     */
    private volatile String version;

    private String provenance() {
        if (version != null) {
            return version;
        }
        // A failure to work it out must not cost a settlement: a row recorded without its version
        // is worse than one recorded with it, and a row not recorded at all is worse than both.
        String named;
        try {
            named = provenance == null ? "" : provenance.get();
        } catch (RuntimeException cannotTell) {
            named = "";
        }
        version = named == null ? "" : named;
        return version;
    }

    /**
     * A record with no pipeline behind it, whose rows carry no fingerprint.
     *
     * <p>For a writer that is not one run of the work: a supervisor's own journal, and a reader
     * opening a lane's file to summarise it. Neither settles anything, so neither has a pipeline to
     * name.
     */
    public JsonlTrace(Path trace, Path settlements, String key) {
        this(trace, settlements, key, null, List.of(), List.of());
    }

    /**
     * The same, for a run, with the two things only the pipeline can supply.
     *
     * <p>THE PROVENANCE AND THE RANKING ARRIVE TOGETHER, and that is not tidiness. Forgetting the
     * word lists fails silently: nothing throws, no row looks wrong, and what changes is which
     * lines survive the budget in {@link #happened}, so agents quietly stop being shown the
     * verdicts and the objections and start being shown file reads. The measured cost of that is in
     * {@link #happened}. A pipeline that has a name for itself has prompts too, so this asks for
     * both or neither.
     *
     * @param provenance JSON fields naming the pipeline, without a leading comma, or null for none
     * @param decisive   lower-case substrings that mark a line as a verdict or a failure
     * @param disputed   substrings that mark a line as an objection; case does not matter
     */
    public JsonlTrace(Path trace, Path settlements, String key, Supplier<String> provenance,
                      List<String> decisive, List<String> disputed) {
        this.trace = trace;
        this.settlements = settlements;
        this.key = key;
        this.provenance = provenance;
        // LOWERCASED HERE RATHER THAN ASKED FOR IN A COMMENT. `rank` matches against a lowercased
        // line, and the parameters were documented as "lower-case substrings" — a requirement a
        // caller meets by reading the javadoc and violates by copying the phrase out of its own
        // prompts, where it is written for a model to read. This library's own test fixtures use
        // `FAIL_`. A phrase in the wrong case does not fail, it silently stops promoting the lines
        // it names, and the run's summary quietly loses its verdicts to routine file reads.
        this.decisive = lowered(decisive);
        this.disputed = lowered(disputed);
    }

    /**
     * The run reading its own record back, one line per event, newest last.
     *
     * <p>SUMMARISED, HARD. A trace row carries whole prompts and whole tool results, and this is
     * read INTO a prompt: returning rows whole would put the conversation inside itself and grow
     * the context faster than anything else here. One line each is enough to see what was tried,
     * what was objected to and what a tool answered; the file itself keeps everything.
     */
    @Override
    public String happened(String stage, String agent, int limit) {
        if (!Files.isReadable(trace)) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        try (var rows = Files.lines(trace)) {
            for (String row : rows.toList()) {
                if (!row.contains("\"bump\":\"" + key.replace("\"", "") + "\"")
                        && !row.contains(escape(key))) {
                    continue;
                }
                String kind = value(row, "kind");
                String who = value(row, "agent");
                String where = value(row, "stage");
                if (!stage.isBlank() && !stage.equalsIgnoreCase(where)) {
                    continue;
                }
                if (!agent.isBlank() && !agent.equalsIgnoreCase(who)) {
                    continue;
                }
                String line = switch (kind) {
                    case "asked" -> "[" + who + "] answered: " + one(value(row, "reply"));
                    case "applied" -> "[" + where + "] " + one(value(row, "what"));
                    case "tool" -> "[" + who + "] " + value(row, "tool") + "("
                            + one(value(row, "arguments")) + ") -> " + one(value(row, "result"));
                    case "progress" -> "* " + one(value(row, "note"));
                    case "built" -> "[build " + value(row, "phase") + "] "
                            // READ, NOT GREPPED, AND THE GREP WAS WRONG. built() writes
                            // "infra":"true" — a quoted string, because of() takes String values —
                            // and this looked for "infra":true. So the "did not run" branch was
                            // unreachable: every build that never ran, a missing toolchain or an
                            // absent dependency, was summarised as one that ran and failed. Those
                            // call for opposite responses, and the summary is what a person reads
                            // to decide. Found by a mutation that could not be killed, because a
                            // branch nothing can reach cannot be made to behave differently.
                            + ("true".equals(value(row, "infra")) ? "did not run" : "ran");
                    case "settled" -> "SETTLED " + value(row, "state") + ": "
                            + one(value(row, "because"));
                    default -> "";
                };
                if (!line.isBlank()) {
                    lines.add(rank(kind, line) + "\u0000" + line);
                }
            }
        } catch (IOException unreadable) {
            return "";
        }
        // MOST IMPORTANT FIRST, THEN BACK INTO ORDER. Returned chronologically, the budget went on
        // whatever happened to be recent: measured over five real calls, 13 of 349 returned lines
        // carried a decision and the rest was an hour-old reader reading build files. Ranking first
        // and re-sorting after means the same budget carries the verdicts, the objections and the
        // failures, with the routine filling whatever room is left.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            int byRank = lines.get(b).charAt(0) - lines.get(a).charAt(0);
            return byRank != 0 ? byRank : b - a;
        });
        List<Integer> kept = new ArrayList<>(order.subList(0, Math.min(order.size(),
                Math.max(1, limit))));
        kept.sort(Integer::compareTo);
        StringBuilder out = new StringBuilder();
        for (int i : kept) {
            String line = lines.get(i);
            out.append(line, line.indexOf('\u0000') + 1, line.length()).append('\n');
        }
        return out.toString().strip();
    }

    /**
     * How much a line is worth keeping when the budget runs out.
     *
     * <p>A verdict, an objection and a failure are what a reader came for. A tool call is the bulk
     * of any trace and almost never the point, which is why an unranked log spends its whole budget
     * on file reads.
     *
     * <p>The kinds are ranked here and the PHRASES come from the caller, because which words mean
     * "this decided something" is written in the caller's own prompts and nowhere else.
     */
    private char rank(String kind, String line) {
        String low = line.toLowerCase(java.util.Locale.ROOT);
        if (kind.equals("settled") || any(low, decisive)) {
            return '5';
        }
        if (any(low, disputed)) {
            return '4';
        }
        if (kind.equals("progress")) {
            return '3';
        }
        if (kind.equals("asked") || kind.equals("applied")) {
            return '2';
        }
        return '1';
    }

    private static List<String> lowered(List<String> phrases) {
        if (phrases == null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>(phrases.size());
        for (String phrase : phrases) {
            out.add(phrase.toLowerCase(java.util.Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private static boolean any(String lowered, List<String> phrases) {
        for (String phrase : phrases) {
            if (lowered.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /** One line, short enough that a hundred of them are still a summary. */
    private static String one(String text) {
        String flat = text.replace("\\n", " ").replace('\n', ' ').strip();
        return flat.length() > 180 ? flat.substring(0, 180) + " ..." : flat;
    }

    /** A field out of a written row, without parsing JSON the writer already knows the shape of. */
    /**
     * ONE FIELD OF A RECORDED ROW, READ WITH THE READER THE ROW WAS WRITTEN WITH.
     *
     * <p>THIS WAS A THIRD COPY OF A STRING SCANNER AND IT HAD DRIFTED. {@link Json#read} is one,
     * {@link Json#row} is another, and this was the third — in the same package, over the same
     * rows, disagreeing about what they said. Measured against what {@link Settlement#escape}
     * actually writes:
     *
     * <pre>
     * written            Json.read gives   this used to give
     * "line\nbreak"      line[NL]break     line break
     * "bell&#92;u0007ring" bell[BEL]ring     bellu0007ring
     * </pre>
     *
     * <p>The second row is the bug: no unicode-escape case existed, so the backslash was
     * the four hex digits were kept as letters. A control character in an agent's name or a stage
     * key therefore made {@link #happened} match a key that does not exist, and the summary it
     * builds is what a person reads to find out why a run is still going.
     *
     * <p>The first row is NOT a bug and is why the two were ever different: this feeds a ONE-LINE
     * summary, so a newline has to become a space. That is a display decision, and mixing it into
     * the parse is what let the parse drift. Parsed correctly here, flattened afterwards.
     */
    private static String value(String row, String key) {
        return Json.read(row, key).replace('\n', ' ').replace('\t', ' ').replace('\r', ' ');
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void asked(String agent, String prompt, String reply) {
        // IN FULL, both of them. Truncating here would save disk and cost the corpus.
        write("asked", of("agent", agent, "prompt", prompt, "reply", reply));
    }

    @Override
    public void applied(String stage, String what) {
        write("applied", of("stage", stage, "what", what));
    }

    @Override
    public void thought(String finishReason, String thinking, String content) {
        write("thought", of("finish", finishReason, "thinking", thinking, "content", content));
    }

    @Override
    public void tool(String agent, String tool, String arguments, String result) {
        write("tool", of("agent", agent, "tool", tool, "arguments", arguments, "result", result));
    }

    // --- what the agent loop reports while it runs; its payloads arrive shortened ---

    @Override
    public void onToolInvocation(String context, String toolName, Object memoryId,
                                 String argumentsTruncated, String resultTruncated) {
        // Recorded already at the executor; writing the shortened duplicate would double the file.
    }

    @Override
    public void built(String phase, Outcome result) {
        write("built", of("phase", phase, "infra", String.valueOf(result.infra()),
                "passed", String.valueOf(result.passed()), "summary", result.summary()));
    }

    @Override
    public void settled(String runKey, String state, String because, boolean beforeOk,
                        boolean afterOk) {
        settled(runKey, state, because, beforeOk, afterOk, false);
    }

    @Override
    public void settled(String runKey, String state, String because, boolean beforeOk,
                        boolean afterOk, boolean resumed) {
        write("settled", of("state", state, "because", because,
                "baseline", String.valueOf(beforeOk), "gate", String.valueOf(afterOk),
                "resumed", String.valueOf(resumed)));
        Settlement.settled(settlements, runKey, state, because, beforeOk, afterOk, provenance(),
                resumed);
    }

    @Override
    public void failed(String runKey, Throwable cause) {
        String what = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        StringBuilder where = new StringBuilder(what);
        for (StackTraceElement s : cause.getStackTrace()) {
            where.append("\n  at ").append(s);
        }
        if (cause.getCause() != null) {
            where.append("\ncaused by ").append(cause.getCause());
        }
        write("failed", of("cause", what, "stack", where.toString()));
        Settlement.note(settlements, runKey, "infra", what);
    }

    @Override
    public void progress(String runKey, String note) {
        write("progress", of("note", note));
        // "bumping" IS A STATE VALUE ON THE WIRE, like the field names in Settlement.write, and it
        // is spelt in the first consumer's vocabulary rather than this library's. A reader tells a
        // unit of work that is still going from one that has settled by this exact string.
        Settlement.note(settlements, runKey, "bumping", note, false, false, provenance());
    }

    @Override
    public void priced(String runKey, String minutes, String itemisation) {
        write("priced", of("minutes", minutes, "itemisation", itemisation));
    }

    @Override
    public void exchanged(Exchange e) {
        write("exchange", of(
                "direction", e.direction(),
                "agent", e.agent(),
                "messages", String.valueOf(e.messages()),
                "sent", e.sent(),
                "got", e.got(),
                "tools", e.tools(),
                "finish", e.finish(),
                "in", String.valueOf(e.inTokens()),
                "out", String.valueOf(e.outTokens()),
                "ms", String.valueOf(e.ms()),
                "error", e.error()));
    }

    /** A map that tolerates a null value, because an empty model answer is a judgement, not a crash. */
    private static Map<String, String> of(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return m;
    }

    private void write(String kind, Map<String, String> fields) {
        Map<String, String> row = new LinkedHashMap<>();
        // WHEN, on every event: "how long" is half of what anyone reads a trace for.
        row.put("at", String.valueOf(System.currentTimeMillis()));
        // THE WIRE NAME, AND IT IS NOT THIS LIBRARY'S TO CHOOSE. The first consumer's launcher
        // decides a unit of work is finished by grepping this exact string out of the settlements
        // file, and bash re-reads a running script by byte offset, so that launcher cannot be
        // corrected while it is up. Rename the field in Java as much as you like; never the string.
        row.put("bump", key);
        row.put("kind", kind);
        row.putAll(fields);
        StringBuilder b = new StringBuilder("{");
        row.forEach((k, v) -> {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append('"').append(k).append("\":\"").append(Settlement.escape(v)).append('"');
        });
        try {
            if (trace.getParent() != null) {
                Files.createDirectories(trace.getParent());
            }
            Files.writeString(trace, b.append("}\n"),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // A trace that cannot be written must not end a run that is otherwise fine, but say so:
            // a silently absent trace is worse than a loud one.
            System.err.println("trace: " + e.getMessage());
        }
    }
}
