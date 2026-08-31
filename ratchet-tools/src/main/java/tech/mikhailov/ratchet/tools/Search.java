package tech.mikhailov.ratchet.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import tech.mikhailov.ratchet.llm.Called;
import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Tool;

/**
 * GREP AND GLOB: FIND A NAME ACROSS A TREE, AND FIND FILES BY PATH.
 *
 * <p>WHY THESE EXIST WHEN {@code TOOLS.md} SAID TO LEAVE grep OUT. That measurement is real — three
 * calls in six runs, and {@code bash} can do it — but it is one agent shape, and a second corpus
 * says the opposite loudly enough to settle it. 11,328 calls across 24 lanes of a fake-writing
 * pipeline: {@code grep} SECOND at 1,797 calls and 15.9%, present in 24 of 24 lanes, with
 * {@code glob} at 988 and 8.7%, also 24 of 24. Three orders of magnitude apart from the first
 * measurement, which means the two corpora are not disagreeing about a tool — they are describing
 * different work.
 *
 * <p>The difference is the question the agent is holding. An agent navigating a repository it
 * already understands reads and runs. An agent asked what shape a missing type must be has exactly
 * one way to answer: every use site of a name across a tree. {@code grep} IS that question and
 * {@code read} is what you do once it has answered — together 61% of everything that pipeline does.
 *
 * <p>AND THE REASON FOR OMITTING IT WAS WORST FOR THE CALLER MOST LIKELY TO NEED IT. "bash can do
 * it" holds only for a caller who takes {@code bash}. The caller who refuses a shell — because
 * every guard they have is enforced at the tool boundary, which is the honest reason to refuse one
 * — was being told to search with a tool they had already ruled out, and so had no search at all.
 * Two defensible decisions that combined into an indefensible one. These live beside the file tools
 * for that reason: {@link Kit#withoutShell} hands out a set that can still find things.
 *
 * <p>THE SCHEMAS ARE dsh'S. {@code pattern}, {@code path}, {@code include} for grep; {@code
 * pattern}, {@code path} for glob. So are the caps — 250 matches, 2,000 bytes of one matched line,
 * 100 paths, 30 seconds — because a model that has met theirs should meet the same numbers here.
 *
 * <p>WHAT IS NOT dsh'S IS THE ENGINE, AND THE DIFFERENCES ARE THE MODEL'S TO KNOW. They spawn a
 * packaged ripgrep; this walks the tree with {@code java.nio} and matches with
 * {@link java.util.regex}, because this module ships no binaries and because a caller who refused a
 * shell has not gained one by way of a search tool. Three consequences, each documented where it
 * bites: the regex dialect is Java's rather than Rust's, {@code .gitignore} is NOT consulted, and a
 * backtracking pattern is stopped by a clock instead of being impossible.
 *
 * @see Kit for the assembled set
 * @see Workspace for the file tools these sit beside
 */
public final class Search {

    /** dsh's {@code GREP_MAX_MATCHES}, which is in turn Claude Code's {@code head_limit}. */
    static final int MOST_MATCHES = 250;

    /** dsh's {@code GREP_MAX_LINE_BYTES}. A minified bundle is one line and it is not evidence. */
    static final int MOST_LINE = 2_000;

    /** dsh's {@code GLOB_MAX_RESULTS}. */
    static final int MOST_PATHS = 100;

    /**
     * HOW MANY FILES ONE CALL VISITS BEFORE IT STOPS AND SAYS SO.
     *
     * <p>Not dsh's, because they bound ripgrep's output bytes and this bounds a list held in the
     * JVM's heap. It is here because this library has already killed a JVM once with something
     * unbounded, and an {@code OutOfMemoryError} is an Error — the tool loop does not catch it, so
     * it takes the whole run rather than the call.
     */
    static final int MOST_FILES = 100_000;

    /** dsh's {@code SEARCH_TIMEOUT_MS}: a search that has not answered in this long has failed. */
    public static final Duration LONGEST = Duration.ofSeconds(30);

    /** Version-control metadata, which is never what a model meant and is enormous. */
    private static final Set<String> SKIPPED = Set.of(".git", ".hg", ".svn");

    /** How often the backtracking guard looks at the clock. Often enough; not on every character. */
    private static final int EVERY = 4_096;

    private static final String GREP_SCHEMA = """
            {"type":"object","properties":{
            "pattern":{"type":"string","description":"A regular expression to look for."},
            "path":{"type":"string","description":"File or directory to search. Default the root."},
            "include":{"type":"string","description":"Glob limiting which files are searched, e.g. *.java"}},
            "required":["pattern"]}""";

    private static final String GLOB_SCHEMA = """
            {"type":"object","properties":{
            "pattern":{"type":"string","description":"A glob matched against paths, e.g. **/*Test.java"},
            "path":{"type":"string","description":"Directory to search under. Default the root."}},
            "required":["pattern"]}""";

    private final Rooted rooted;
    private final Duration longest;

    /** Rooted at {@code root}, with dsh's thirty seconds for one call. */
    public Search(Path root) {
        this(root, LONGEST);
    }

    /**
     * The same, with the per-call deadline chosen.
     *
     * <p>{@code longest} is a real bound rather than a courtesy: it is what stops a broad pattern
     * over a large tree, and it is what stops a backtracking one over a single long line. A caller
     * whose tree is enormous should raise it deliberately rather than discover it.
     */
    public Search(Path root, Duration longest) {
        this.rooted = new Rooted(root);
        this.longest = longest == null || longest.isNegative() || longest.isZero()
                ? LONGEST : longest;
    }

    Search(Rooted rooted, Duration longest) {
        this.rooted = rooted;
        this.longest = longest == null || longest.isNegative() || longest.isZero()
                ? LONGEST : longest;
    }

    /** Both, in the order a model reaches for them: find the text, then find the files. */
    public Map<Tool, Calling> tools() {
        Map<Tool, Calling> all = new LinkedHashMap<>();
        all.put(new Tool("grep",
                "Search file contents with a regular expression. Results are grouped by file with "
                        + "the line number of each match. Narrow a broad search with path (where to "
                        + "look) and include (a glob for which files), because a search that finds "
                        + "everything has told you nothing. Ignored and hidden files ARE searched: "
                        + ".gitignore is not consulted, so use include to skip build output.",
                GREP_SCHEMA), answering(this::grep));
        all.put(new Tool("glob",
                "Find files whose path matches a glob, newest first. Use ** to cross directories: "
                        + "**/*Test.java finds them at any depth, src/*.java only directly in src. "
                        + "A pattern with no slash in it matches a file name at any depth. This "
                        + "finds files by PATH; grep finds them by CONTENT.",
                GLOB_SCHEMA), answering(this::glob));
        return all;
    }

    /**
     * EVERY MATCH, GROUPED BY FILE, WITH THE LINE NUMBER — because the line number is the
     * coordinate the compiler, the stack trace and the {@code read} tool all already speak.
     *
     * <p>THE COUNT IS OF EVERYTHING FOUND, NOT OF WHAT IS SHOWN. A model told "250 matches" when
     * there were 1,842 will believe it has seen the set and reason about the absence of the rest.
     * Both numbers go in the footer, which is why the scan continues counting after the page is
     * full: the extra work buys the one fact that makes the page safe to reason from.
     *
     * <p>THE REGEX DIALECT IS JAVA'S, which is practically a superset of ripgrep's: lookaround and
     * backreferences work here and would be REFUSED there, so a pattern written against this may
     * not travel back. The superset is also where the danger is — ripgrep refuses backreferences
     * partly because they are the construct no engine can bound — and {@link Ticking} is what
     * stands in for that refusal.
     */
    private String grep(Called call) throws IOException {
        String args = call.arguments();
        String regex = Args.need(args, "pattern");
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException bad) {
            return "pattern is not a usable regular expression: " + bad.getDescription()
                    + ", at index " + bad.getIndex() + " of " + Retain.glance(regex)
                    + ". Plain text with no metacharacters is a valid pattern; escape "
                    + "\\ ( ) [ ] { } . * + ? ^ $ | with a backslash when you mean them literally.";
        }
        Rooted.Where where = rooted.at(Args.maybe(args, "path", "."));
        if (where.refused()) {
            return where.refusal();
        }
        Path from = where.path();
        if (!Files.exists(from)) {
            return "There is nothing at " + rooted.shown(from) + " to search. list_dir will say "
                    + "what is actually there.";
        }
        String include = Args.maybe(args, "include", "");
        Globbing only;
        try {
            only = include.isEmpty() ? null : new Globbing(include);
        } catch (IllegalArgumentException bad) {
            return "include is not a usable glob: " + Retain.glance(include) + ". It is a file "
                    + "pattern such as *.java or src/**/*.xml, not a regular expression.";
        }
        long deadline = System.nanoTime() + longest.toNanos();
        List<Found> files = under(from, only, deadline);

        List<Hit> hits = new ArrayList<>();
        int seen = 0;
        int searched = 0;
        int binary = 0;
        int unreadable = 0;
        boolean ranLong = false;
        Ticking clock = new Ticking(deadline);
        for (Found file : files) {
            if (past(deadline)) {
                ranLong = true;
                break;
            }
            searched++;
            try (BufferedReader lines = Files.newBufferedReader(file.path(),
                    StandardCharsets.UTF_8)) {
                String line;
                for (int n = 1; (line = lines.readLine()) != null; n++) {
                    if (pattern.matcher(clock.over(line)).find()) {
                        seen++;
                        if (hits.size() < MOST_MATCHES) {
                            hits.add(new Hit(file.path(), n, line));
                        }
                    }
                }
            } catch (MalformedInputException notText) {
                binary++;
            } catch (IOException refused) {
                unreadable++;
            } catch (Overran stillRunning) {
                return "The pattern " + Retain.glance(regex) + " was still running after "
                        + longest.toSeconds() + " seconds inside " + rooted.shown(file.path())
                        + ". It backtracks: nested quantifiers such as (a+)+ or (\\w+\\s?)+ can "
                        + "take exponential time on one long line. Simplify it, or anchor it.";
            }
        }
        if (hits.isEmpty()) {
            return "No matches for " + Retain.glance(regex) + " in " + searched + " files under "
                    + rooted.shown(from) + skippedNote(binary, unreadable)
                    + ". The pattern is a regular expression, so a literal . or ( must be escaped;"
                    + " glob finds files by name if that is what you meant.";
        }
        return Retain.most(grouped(hits))
                + "\n(" + (seen > hits.size() ? hits.size() + " of " + seen + " matches shown"
                        : seen + (seen == 1 ? " match" : " matches"))
                + " in " + files(hits) + ", from " + searched + " files searched"
                + skippedNote(binary, unreadable)
                + (ranLong ? "; the search stopped after " + longest.toSeconds() + " seconds and "
                        + "did not reach every file" : "")
                + (seen > hits.size() ? "; narrow it with path or include" : "") + ")";
    }

    /** Each file's path, then one {@code Line N:} row per match, which is dsh's own layout. */
    private String grouped(List<Hit> hits) {
        StringBuilder out = new StringBuilder();
        Path current = null;
        for (Hit hit : hits) {
            if (!hit.file().equals(current)) {
                out.append(current == null ? "" : "\n").append(rooted.shown(hit.file())).append('\n');
                current = hit.file();
            }
            out.append("Line ").append(hit.line()).append(": ").append(Retain.line(hit.text()))
                    .append('\n');
        }
        return out.toString();
    }

    private static int files(List<Hit> hits) {
        return (int) hits.stream().map(Hit::file).distinct().count();
    }

    private static String skippedNote(int binary, int unreadable) {
        String notText = binary == 0 ? "" : "; " + binary + " skipped as not UTF-8 text";
        String refused = unreadable == 0 ? "" : "; " + unreadable + " could not be read";
        return notText + refused;
    }

    /**
     * PATHS, NEWEST FIRST, BECAUSE THE CAP TAKES THE HEAD.
     *
     * <p>The order is load-bearing rather than cosmetic: 100 of 431 files means the model sees a
     * page, and the useful page in a repository somebody is working in is the one that changed
     * most recently. Sorted oldest-first, the same cap would hand back the files least likely to
     * matter and call it a result.
     */
    private String glob(Called call) throws IOException {
        String args = call.arguments();
        String pattern = Args.need(args, "pattern");
        Globbing matching;
        try {
            matching = new Globbing(pattern);
        } catch (IllegalArgumentException bad) {
            return "pattern is not a usable glob: " + Retain.glance(pattern) + ". It is a path "
                    + "pattern — * within one directory, ** across them, ? one character, {a,b} "
                    + "either — not a regular expression. grep takes those.";
        }
        Rooted.Where where = rooted.at(Args.maybe(args, "path", "."));
        if (where.refused()) {
            return where.refusal();
        }
        Path from = where.path();
        if (!Files.exists(from)) {
            return "There is no directory at " + rooted.shown(from) + " to search under.";
        }
        long deadline = System.nanoTime() + longest.toNanos();
        List<Found> found = under(from, matching, deadline);
        if (found.isEmpty()) {
            return "No files match " + Retain.glance(pattern) + " under " + rooted.shown(from)
                    + ". A pattern with no slash matches a file name at any depth (*.java), and "
                    + "** crosses directories (src/**/*.java); grep searches contents instead.";
        }
        found.sort(Comparator.comparingLong(Found::modified).reversed()
                .thenComparing(entry -> entry.path().toString()));
        StringBuilder out = new StringBuilder();
        for (Found entry : found.subList(0, Math.min(MOST_PATHS, found.size()))) {
            out.append(rooted.shown(entry.path())).append('\n');
        }
        return Retain.most(out.toString())
                + "(" + (found.size() > MOST_PATHS
                        ? MOST_PATHS + " of " + found.size() + " files shown, newest first; "
                                + "narrow the pattern"
                        : found.size() + (found.size() == 1 ? " file" : " files") + " under "
                                + rooted.shown(from) + ", newest first")
                + (found.size() >= MOST_FILES ? "; the walk stopped at " + MOST_FILES + " files"
                        : "")
                + ")";
    }

    /**
     * EVERY REGULAR FILE UNDER {@code from} THAT {@code only} ACCEPTS, WITH ITS MODIFICATION TIME.
     *
     * <p>The time comes from the attributes the visitor is already handed, so ordering by it costs
     * nothing; asking for it afterwards would be one extra stat per file.
     *
     * <p>Symbolic links are not followed, so a link out of the root is listed and not descended.
     * That is narrower than {@link Rooted}, which cannot see links at all — worth saying because it
     * means these two tools are the ones that do NOT walk out of the workspace, and the file tools
     * beside them still can.
     */
    private List<Found> under(Path from, PathMatcher only, long deadline) throws IOException {
        List<Found> found = new ArrayList<>();
        if (!Files.isDirectory(from)) {
            if (only == null || only.matches(from.getFileName())) {
                found.add(new Found(from, Files.getLastModifiedTime(from).toMillis()));
            }
            return found;
        }
        Files.walkFileTree(from, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                if (name != null && SKIPPED.contains(name.toString()) && !dir.equals(from)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return past(deadline) ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()
                        && (only == null || only.matches(from.relativize(file)))) {
                    found.add(new Found(file, attrs.lastModifiedTime().toMillis()));
                }
                return found.size() >= MOST_FILES || past(deadline)
                        ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            /** A file that cannot be stat'ed is skipped: it is not a failure of the search. */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException problem) {
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static boolean past(long deadline) {
        return System.nanoTime() - deadline > 0;
    }

    /** One match: where it was, which line, and the line itself. */
    private record Hit(Path file, int line, String text) {
    }

    /** One file the walk accepted, with the time the ordering needs. */
    private record Found(Path path, long modified) {
    }

    /**
     * A GLOB AS A MODEL MEANS IT, WHICH IS NOT QUITE WHAT {@code getPathMatcher} MEANS BY IT.
     *
     * <p>Java's matcher is strict about depth: {@code glob:*.java} matches {@code X.java} and not
     * {@code src/X.java}, and {@code glob:**}{@code /*.java} requires a slash, so it matches
     * {@code src/X.java} and NOT {@code X.java} at the top. Models write both idioms expecting
     * gitignore semantics, where a pattern with no slash matches at any depth and a leading
     * {@code **}{@code /} means "zero or more directories". So the pattern is tried three ways:
     * against the whole relative path, against the file name alone when the pattern has no slash
     * in it, and — for a leading {@code **}{@code /} — against both with the prefix removed.
     *
     * <p>Erring toward matching is the right direction here. A glob that finds a few extra files
     * costs a model one look; a glob that silently finds nothing costs it the belief that the file
     * is not there.
     */
    private static final class Globbing implements PathMatcher {

        private final PathMatcher whole;
        private final PathMatcher bare;
        private final boolean anyDepth;

        Globbing(String pattern) {
            this.whole = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            this.bare = pattern.startsWith("**/")
                    ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
                    : null;
            this.anyDepth = !pattern.contains("/");
        }

        @Override
        public boolean matches(Path relative) {
            if (relative == null) {
                return false;
            }
            if (whole.matches(relative)) {
                return true;
            }
            Path name = relative.getFileName();
            if (name == null) {
                return false;
            }
            if (anyDepth && whole.matches(name)) {
                return true;
            }
            return bare != null && (bare.matches(relative) || bare.matches(name));
        }
    }

    /**
     * THE CLOCK A BACKTRACKING PATTERN IS READ THROUGH, so a bad regex ends the CALL and not the RUN.
     *
     * <p>{@link java.util.regex} cannot be interrupted and does not fail: a pattern that backtracks
     * runs for longer than anybody will wait, holding the thread, and a model is perfectly capable
     * of writing one. The deadline elsewhere in this class is checked between files and between
     * lines, and neither helps, because the whole of the time is spent inside a single
     * {@code find()}.
     *
     * <p>WHAT IS ACTUALLY STILL DANGEROUS IS NARROWER THAN THE TEXTBOOK SAYS, and it was worth
     * measuring rather than assuming. The classic examples are defused on a current JDK:
     * {@code (x+x+)+y} over sixty characters, {@code ^(a+)+$}, {@code ([a-zA-Z]+)*$},
     * {@code ^(a|a)*$} and {@code (a?){25}a{25}} all answer in about a millisecond, because the
     * engine memoises the states it has already failed from. What memoisation cannot cover is a
     * BACKREFERENCE, whose meaning depends on what was captured on this path: {@code (a+)+\1b} over
     * thirty characters was measured at 50.6 seconds, and it grows exponentially from there.
     *
     * <p>So this guard is narrow, and it is also unavoidable: a backreference is precisely the
     * construct ripgrep refuses to compile and Java compiles happily. Taking Java's engine buys the
     * wider dialect and inherits the one hole in the engine's own defence.
     *
     * <p>So the matcher reads the line through this instead of directly, and the clock is looked at
     * every {@link #EVERY} characters it asks for — often enough to bound the call at a second or
     * so past the deadline, rarely enough that a normal search does not pay for it. The throw
     * carries no stack trace because nothing reads one; the sentence the model gets back names the
     * file and says the word "backtracks", which is the fact it needs to write a better pattern.
     */
    private static final class Ticking {

        private final long deadline;
        private int reads;

        Ticking(long deadline) {
            this.deadline = deadline;
        }

        CharSequence over(String line) {
            return new Watched(line);
        }

        private final class Watched implements CharSequence {

            private final CharSequence text;

            Watched(CharSequence text) {
                this.text = text;
            }

            @Override
            public int length() {
                return text.length();
            }

            @Override
            public char charAt(int index) {
                if (++reads >= EVERY) {
                    reads = 0;
                    if (past(deadline)) {
                        throw new Overran();
                    }
                }
                return text.charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return new Watched(text.subSequence(start, end));
            }

            @Override
            public String toString() {
                return text.toString();
            }
        }
    }

    /** A pattern that ran past the deadline. Not a fault, and nothing reads its stack. */
    private static final class Overran extends RuntimeException {

        Overran() {
            super(null, null, false, false);
        }
    }

    /** As {@link Workspace} does it: one body, allowed to throw what the filesystem throws. */
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

    @FunctionalInterface
    private interface Answering {

        String to(Called call) throws IOException;
    }
}
