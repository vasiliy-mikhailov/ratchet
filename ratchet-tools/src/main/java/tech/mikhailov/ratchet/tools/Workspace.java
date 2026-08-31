package tech.mikhailov.ratchet.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.mikhailov.ratchet.llm.Called;
import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Tool;
import tech.mikhailov.ratchet.record.Json;

/**
 * READ, WRITE, EDIT AND LIST_DIR, ROOTED AT ONE DIRECTORY.
 *
 * <p>THE SCHEMAS ARE dsh'S, FIELD FOR FIELD. {@code file_path}, {@code offset}, {@code limit},
 * {@code old_string}, {@code new_string}, {@code replace_all} — snake_case, and named exactly as a
 * model already meets them elsewhere. A model that has used theirs has used these: it arrives
 * knowing the offset is 1-based, that the view comes back numbered, and that an edit matches
 * literally rather than as a pattern. Renaming any of it for house style spends that for nothing
 * and asks every model that shows up to guess.
 *
 * <p>NOTHING HERE THROWS BECAUSE A MODEL WAS WRONG. A path outside the root, a file that is not
 * there, an {@code old_string} that matches four times, a directory handed to {@code read} — each
 * is answered with a sentence saying what happened and what would work instead, because that
 * sentence is the whole of what the model has to correct itself with. {@code Asking} catches a
 * throw and hands its message back anyway, so the choice is not between an answer and a crash; it
 * is between a sentence written for a reader and whatever a stack trace's first line happens to
 * say. The one thing raised from here is a null root, which is the caller's own wiring.
 *
 * <p>RESULTS NAME PATHS RELATIVE TO THE ROOT. It is the short form, it is the form the next call
 * can pass straight back, and it keeps a home directory out of a corpus that will be read by
 * somebody else. The absolute path appears in exactly one message — the refusal for a path that
 * left the root — because there the two paths ARE the evidence.
 *
 * @see Kit for the assembled set, and for why this module exists at all
 */
public final class Workspace {

    /**
     * TWO THOUSAND LINES, WHICH IS dsh'S NUMBER, AND IT IS BOTH THE DEFAULT AND THE CAP.
     *
     * <p>A model that asks for 50,000 lines is asking for a context it cannot afford and will
     * re-prefill on every call that follows. Answering with 2,000 and a footer naming the next
     * offset is more useful than refusing, because it is progress plus the instruction for the
     * rest, and the model does not have to be told twice.
     */
    static final int MOST_LINES = 2_000;

    /** The gutter {@code cat -n} uses, so a numbered view looks like every other numbered view. */
    private static final int GUTTER = 6;

    private static final String READ_SCHEMA = """
            {"type":"object","properties":{
            "file_path":{"type":"string","description":"The file to read."},
            "offset":{"type":"number","description":"1-based line to start at. Default 1."},
            "limit":{"type":"number","description":"Lines to return. Default and max 2000."}},
            "required":["file_path"]}""";

    private static final String WRITE_SCHEMA = """
            {"type":"object","properties":{
            "file_path":{"type":"string","description":"The file to write."},
            "content":{"type":"string","description":"The whole new contents of the file."}},
            "required":["file_path","content"]}""";

    private static final String EDIT_SCHEMA = """
            {"type":"object","properties":{
            "file_path":{"type":"string","description":"The file to edit."},
            "old_string":{"type":"string","description":"Text to replace, matched literally."},
            "new_string":{"type":"string","description":"What to put in its place."},
            "replace_all":{"type":"boolean","description":"Replace every occurrence."}},
            "required":["file_path","old_string","new_string"]}""";

    private static final String LIST_SCHEMA = """
            {"type":"object","properties":{
            "path":{"type":"string","description":"The directory to list."}},
            "required":["path"]}""";

    /** The root check, which {@link Search} shares, because a guard written twice drifts. */
    private final Rooted rooted;

    /**
     * @param root the directory every path is resolved against. It is not required to exist yet:
     *             {@code write} creates parents, and a run whose workspace is made on the first
     *             write is a run this library already has.
     */
    public Workspace(Path root) {
        this.rooted = new Rooted(root);
    }

    Workspace(Rooted rooted) {
        this.rooted = rooted;
    }

    /** Where this is rooted, for a caller that wants to say so in a prompt. */
    public Path root() {
        return rooted.path();
    }

    /** All four, in the order a model meets them in a system prompt: look, make, change, look. */
    public Map<Tool, Calling> tools() {
        Map<Tool, Calling> all = new LinkedHashMap<>();
        all.put(new Tool("read",
                "Read a file. The view is line-numbered from 1, and its footer says which "
                        + "lines you were shown and how many the file has. Use offset and limit "
                        + "to page through a long file; limit defaults to 2000 and caps there.",
                READ_SCHEMA), answering(this::read));
        all.put(new Tool("write",
                "Write a file, creating it or replacing all of it. Missing parent directories are "
                        + "created. To change part of a file use edit: write does not merge, it "
                        + "replaces.",
                WRITE_SCHEMA), answering(this::write));
        all.put(new Tool("edit",
                "Replace old_string with new_string in a file. The match is literal, not a "
                        + "pattern: every space, tab and newline counts. old_string must occur "
                        + "exactly once, so include the surrounding lines needed to make it "
                        + "unique; if you mean every occurrence, pass replace_all true.",
                EDIT_SCHEMA), answering(this::edit));
        all.put(new Tool("list_dir",
                "List a directory. Directories are marked with a trailing slash.",
                LIST_SCHEMA), answering(this::list));
        return all;
    }

    /**
     * NUMBERED LINES AND A FOOTER, AND NEITHER IS DECORATION.
     *
     * <p>The number is the coordinate everything else in a run already speaks: a compiler error, a
     * stack trace, a diff hunk and the model's own note to itself all say "line 412". A plain dump
     * makes it count lines by hand, and it counts them wrong.
     *
     * <p>THE FOOTER IS APPENDED AFTER THE BOUND, DELIBERATELY. {@link Retain#most} keeps the HEAD
     * of a text, so a footer written inside the bounded body is the first thing a long page loses —
     * and it is the one line that says how to get the rest. Bounded body, then footer, means the
     * result can run about 200 characters past {@link Retain#MOST} and always ends with the
     * instruction for the next call.
     *
     * <p>THE FILE IS STREAMED TWICE RATHER THAN HELD ONCE. The footer promises a total, which needs
     * a full pass, and {@code Files.readAllLines} on the 400 MB generated file that exists in every
     * real corpus is an {@code OutOfMemoryError} — an Error, which the tool loop does not catch, so
     * it takes the whole run and not just the call. Two passes cost the read twice and bound the
     * memory to one line. One line is still unbounded, which is the honest edge of this: a file
     * with no newline in it is held whole.
     */
    private String read(Called call) throws IOException {
        String given = Args.need(call.arguments(), "file_path");
        Rooted.Where where = at(given);
        if (where.refused()) {
            return where.refusal();
        }
        Path file = where.path();
        if (Files.isDirectory(file)) {
            return shown(file) + " is a directory, not a file. list_dir will say what is in it.";
        }
        if (!Files.exists(file)) {
            return "There is no file at " + shown(file) + ". list_dir on "
                    + shown(file.getParent() == null ? rooted.path() : file.getParent())
                    + " will say what is actually there.";
        }
        int total;
        try {
            total = linesOnDisk(file);
        } catch (MalformedInputException notText) {
            return shown(file) + " is not UTF-8 text: " + Files.size(file) + " bytes that do not "
                    + "decode, so it is almost certainly binary. read returns text only.";
        }
        if (total == 0) {
            return shown(file) + " exists and is empty: 0 lines, " + Files.size(file) + " bytes.";
        }
        int from = Math.max(1, Args.number(call.arguments(), "offset", 1));
        if (from > total) {
            return shown(file) + " has " + total + " lines, so offset " + from + " is past its "
                    + "end. Read from 1 for the start, or " + total + " for the last line.";
        }
        int asked = Args.number(call.arguments(), "limit", MOST_LINES);
        int to = Math.min(total, from + (asked < 1 ? MOST_LINES : Math.min(asked, MOST_LINES)) - 1);
        StringBuilder view = new StringBuilder();
        int last = from - 1;
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            for (int n = 1; (line = lines.readLine()) != null && n <= to; n++) {
                if (n < from) {
                    continue;
                }
                // THE FIRST LINE ALWAYS COMES BACK, however long it is. A page that returns nothing
                // because line one is a minified bundle tells the model only that it failed.
                if (n > from && view.length() + line.length() > Retain.MOST) {
                    break;
                }
                view.append(numbered(n, line)).append('\n');
                last = n;
            }
        }
        return Retain.most(view.toString()) + footer(from, last, to, total);
    }

    /** What the page was, what is left, and the offset that continues it. */
    private static String footer(int from, int last, int to, int total) {
        String stopped = last < to
                ? "; the page stopped at " + Retain.MOST + " characters, short of the "
                        + (to - from + 1) + " lines asked for"
                : "";
        String next = last < total
                ? "; read again with offset " + (last + 1) + " for the rest"
                : ", to the end of the file";
        return "\n(lines " + from + "-" + last + " of " + total + stopped + next + ")";
    }

    /**
     * CREATE OR REPLACE ENTIRELY, AND THE RESULT SAYS WHICH OF THE TWO IT WAS.
     *
     * <p>A model that believed it was creating a file and in fact replaced one is the failure this
     * sentence exists for, and it is a failure the model can only see if it is told. The size that
     * was there is in the result for the same reason: "replacing 12,004 bytes" is an alarm, and
     * "which did not exist before" is a confirmation.
     *
     * <p>{@code content} is read with {@link Args#maybe} rather than {@link Args#need}, and that is
     * not laxity. An empty file is a legitimate thing to write, and {@code Json.read} returns the
     * empty string for both an absent field and an empty one, so {@code need} could not tell them
     * apart — it would refuse the legitimate call and call it missing.
     */
    private String write(Called call) throws IOException {
        String given = Args.need(call.arguments(), "file_path");
        Rooted.Where where = at(given);
        if (where.refused()) {
            return where.refusal();
        }
        Path file = where.path();
        if (Files.isDirectory(file)) {
            return shown(file) + " is an existing directory, so a file cannot be written over it. "
                    + "Choose a path inside it, or a different name.";
        }
        String content = Args.maybe(call.arguments(), "content", "");
        boolean existed = Files.exists(file);
        long replaced = existed ? Files.size(file) : 0;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return "Wrote " + linesIn(content) + " lines (" + content.length() + " characters) to "
                + shown(file) + (existed ? ", replacing the " + replaced + " bytes that were there."
                : ", which did not exist before.");
    }

    /**
     * A LITERAL REPLACEMENT THAT MUST MATCH EXACTLY ONCE, AND THAT RULE IS THE WHOLE TOOL.
     *
     * <p>Everything this does could be done with {@code sed -i} through the {@code bash} tool in
     * the same kit. The difference is the rule: sed matches a PATTERN, applies it EVERYWHERE, and
     * reports NOTHING. This matches a literal string, refuses when the literal is not unique, and
     * says how many times it found it. A model that meant one place and wrote something matching
     * four gets a sentence naming the four instead of four silent edits.
     *
     * <p>WHAT THAT COST THIS PROJECT TO LEARN: a careless regex ate two whole class bodies in one
     * session. It was written to change one method signature and it matched a brace sequence that
     * occurred twice more; the run carried on afterwards against a file that no longer compiled,
     * reading the compiler's complaints and inventing repairs for code that was no longer there.
     * The uniqueness check is what stops that, and the important half is WHEN it stops it: before
     * the write, with the count in hand, while the file is still the file the model read.
     *
     * <p>{@code replace_all} is the door for when every occurrence really is meant. It is explicit,
     * it is the model's own choice rather than a default, and the result says how many it changed.
     *
     * <p>{@code new_string} is read with {@link Args#maybe} because deleting text is a real edit,
     * and an empty {@code new_string} is how it is spelt. {@code old_string} is {@link Args#need},
     * because an empty one matches at every position in the file.
     */
    private String edit(Called call) throws IOException {
        String args = call.arguments();
        String given = Args.need(args, "file_path");
        Rooted.Where where = at(given);
        if (where.refused()) {
            return where.refusal();
        }
        Path file = where.path();
        if (Files.isDirectory(file)) {
            return shown(file) + " is a directory, not a file.";
        }
        if (!Files.exists(file)) {
            return "There is no file at " + shown(file) + " to edit. write creates a file; edit "
                    + "only changes one that is already there.";
        }
        String old = Args.need(args, "old_string");
        String now = Args.maybe(args, "new_string", "");
        if (old.equals(now)) {
            return "old_string and new_string are identical, so this edit would change nothing in "
                    + shown(file) + ". Send the text you want instead.";
        }
        String before;
        try {
            before = Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException notText) {
            return shown(file) + " is not UTF-8 text, so it cannot be edited as text.";
        }
        int found = occurrences(before, old);
        if (found == 0) {
            return "old_string was not found in " + shown(file) + ". It is matched literally — "
                    + "every space, tab and newline — and what was looked for began: "
                    + Retain.glance(old) + ". read the file and copy the text out of what it "
                    + "returns rather than retyping it.";
        }
        if (found > 1 && !flagged(args, "replace_all")) {
            return "old_string was found " + found + " times in " + shown(file) + ", and edit "
                    + "changes exactly one. Extend it with the lines above or below until it "
                    + "matches once, or pass replace_all true to change all " + found + ".";
        }
        int at = before.indexOf(old);
        String after = found == 1
                ? before.substring(0, at) + now + before.substring(at + old.length())
                : before.replace(old, now);
        Files.writeString(file, after, StandardCharsets.UTF_8);
        return (found == 1
                ? "Replaced 1 occurrence in " + shown(file) + ", at line " + lineOf(before, at)
                        + "."
                : "Replaced all " + found + " occurrences in " + shown(file) + ".")
                + " The file was " + linesIn(before) + " lines and is now " + linesIn(after) + ".";
    }

    /**
     * THE ENTRIES, WITH DIRECTORIES MARKED, AND THE COUNT OUTSIDE THE BOUND.
     *
     * <p>A trailing slash rather than a column of types: it is one character, it is what {@code ls
     * -F} and every shell prompt already use, and it survives being quoted back into a path.
     *
     * <p>The count is appended after {@link Retain#most} for the reason {@code read}'s footer is —
     * a directory of forty thousand entries is exactly the listing that overruns the bound, and
     * "40,000 entries" is the fact that tells a model to narrow rather than to scroll.
     *
     * <p>{@code path} is required by the schema and defaulted here to the root. A model that omits
     * it meant the workspace, because the workspace is the only thing it could have meant, and a
     * refusal would cost a whole turn to establish that.
     */
    private String list(Called call) throws IOException {
        String given = Args.maybe(call.arguments(), "path", ".");
        Rooted.Where where = at(given);
        if (where.refused()) {
            return where.refusal();
        }
        Path dir = where.path();
        if (!Files.exists(dir)) {
            return "There is no directory at " + shown(dir) + ".";
        }
        if (!Files.isDirectory(dir)) {
            return shown(dir) + " is a file, not a directory. read will show what is in it.";
        }
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            entries.forEach(found::add);
        }
        if (found.isEmpty()) {
            return shown(dir) + " is a directory and it is empty.";
        }
        found.sort(Comparator.comparing(entry -> entry.getFileName().toString()));
        StringBuilder listing = new StringBuilder();
        for (Path entry : found) {
            listing.append(entry.getFileName())
                    .append(Files.isDirectory(entry) ? "/" : "")
                    .append('\n');
        }
        String whole = listing.toString();
        String kept = Retain.most(whole);
        // A cut listing ends in the truncation notice rather than a newline, and the count has to
        // start a line of its own either way or it reads as one more entry.
        return kept + (kept.endsWith("\n") ? "" : "\n")
                + "(" + found.size() + " entries in " + shown(dir)
                + (kept.equals(whole) ? "" : "; the listing above stopped at " + Retain.MOST
                + " characters") + ")";
    }

    /**
     * THE ROOT CHECK, WHICH LIVES IN {@link Rooted} because {@link Search} enforces the same one.
     * Everything it does and everything it does not do is documented there, and the "does not" half
     * is the important half.
     */
    private Rooted.Where at(String given) {
        return rooted.at(given);
    }

    /** A path as a result should name it: relative to the root, and "." for the root itself. */
    private String shown(Path path) {
        return rooted.shown(path);
    }

    /**
     * ONE TOOL BODY, ALLOWED TO THROW WHAT THE FILESYSTEM THROWS.
     *
     * <p>{@link Calling} cannot throw {@link IOException} and every one of these does I/O, so
     * without this each body would carry its own try/catch and the four would drift. Two kinds are
     * caught, and both are answers rather than faults: {@link IllegalArgumentException}, which is
     * {@link Args} naming a field the model left out (and {@link InvalidPathException}, which is a
     * subclass of it), and {@link IOException}, which is the operating system declining — a
     * permission, a full disk, a file that vanished between the check and the read.
     *
     * <p>An {@link IOException}'s own message is usually the bare path, so the class name is kept
     * alongside it: {@code AccessDeniedException: build/out} says which of the two happened and
     * {@code build/out} alone does not.
     */
    private static Calling answering(Answering doing) {
        return call -> {
            try {
                return doing.to(call);
            } catch (IllegalArgumentException wrong) {
                return wrong.getMessage();
            } catch (IOException refused) {
                return "The filesystem refused that: " + refused.getClass().getSimpleName() + ": "
                        + refused.getMessage();
            }
        };
    }

    /** What {@link Calling} would be if it could do I/O. */
    @FunctionalInterface
    private interface Answering {

        String to(Called call) throws IOException;
    }

    /**
     * A BOOLEAN THE MODEL WROTE, WHICH ARRIVES UNQUOTED AND WHICH {@link Args#flag} CANNOT SEE.
     *
     * <p>{@code Json.read} stops at anything that does not open with a quote, deliberately, and a
     * JSON boolean opens with a bare {@code t}. So {@code "replace_all": true} — which is what a
     * model writes, because the schema it was given says {@code "type":"boolean"} — reads as
     * absent, and every {@code replace_all} call would quietly do the single-match thing and refuse
     * the edit. That is the same defect {@code Json.number} was carved out to fix for integers, and
     * one tool's {@code limit} was ignored on every call an agent ever made for as long as the tool
     * existed. Measured here: three occurrences, {@code replace_all} true, and the refusal came
     * back anyway.
     *
     * <p>{@link Args#flag} IS STILL ASKED FIRST, so a model that quotes its booleans is answered by
     * the reader written for it. {@code Json.part} is the second look, because it hands back the
     * raw value and the bare word is visible in it. This belongs in {@code Args}, behind a
     * {@code Json.bool} beside {@code Json.number}; it is here because a tool that cannot read its
     * own flag is a worse thing to ship than a helper in the wrong class.
     */
    private static boolean flagged(String args, String name) {
        return Args.flag(args, name) || "true".equalsIgnoreCase(Json.part(args, name).trim());
    }

    /** The {@code cat -n} gutter: right-aligned in six columns, then a tab, then the line. */
    private static String numbered(int line, String text) {
        String number = Integer.toString(line);
        return " ".repeat(Math.max(0, GUTTER - number.length())) + number + "\t" + text;
    }

    /** How many lines the file has, streamed, because the alternative is holding all of it. */
    private static int linesOnDisk(Path file) throws IOException {
        int total = 0;
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (lines.readLine() != null) {
                total++;
            }
        }
        return total;
    }

    /** How many lines a string is, counting a final unterminated one, which an editor also does. */
    private static int linesIn(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int breaks = (int) text.chars().filter(c -> c == '\n').count();
        return text.endsWith("\n") ? breaks : breaks + 1;
    }

    /** Which line an index falls on, 1-based, so an edit can say where it landed. */
    private static int lineOf(String text, int index) {
        return 1 + (int) text.substring(0, index).chars().filter(c -> c == '\n').count();
    }

    /**
     * How many times the literal occurs, counted the way {@code String.replace} consumes them.
     *
     * <p>NON-OVERLAPPING, and it has to be: {@code replace} advances past each match, so counting
     * overlaps would report a number the replacement then contradicts — and the number is what the
     * refusal message asks the model to act on.
     */
    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
