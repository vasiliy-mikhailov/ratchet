package tech.mikhailov.ratchet.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import tech.mikhailov.ratchet.llm.Called;
import tech.mikhailov.ratchet.llm.Spilling;
import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Tool;
import tech.mikhailov.ratchet.record.Json;

/**
 * THE SHELL, WHICH IS 71.3% OF EVERY TOOL CALL AN AGENT MAKES.
 *
 * <p>582 of 816 calls across six runs of one real task were {@code bash}, and it was reached for in
 * 6 runs out of 6 (TOOLS.md at the root). Two fifths of those were file inspection — {@code cat},
 * {@code ls}, {@code find}, {@code grep}, {@code sed} — which is why this package ships no
 * {@code grep} tool of its own: it was called three times in six runs and the shell already does
 * it. Everything else in the same corpus goes through here too: 14% Maven, 13% network fetches, 10%
 * inline Python, 8% Gradle, 6% git.
 *
 * <p>THIS IS NOT A SANDBOX AND NOTHING HERE CONFINES ANYTHING. dsh ships four sandbox packages and
 * a policy layer around its bash tool; this class has a working directory and nothing else. The
 * directory says where a command STARTS. It does not say where the command may go: {@code cd /} is
 * the first four characters of a command string, an absolute {@code workdir} is honoured as
 * written, and a check on either would be theatre — the argument is an unrestricted shell command,
 * so whatever a check refused could be spelt another way in the same string, and a guard that can
 * be walked around by an {@code eval} is worse than no guard because it reads like one.
 *
 * <p>WHAT A CALLER WIRING THIS IS ACCEPTING, PLAINLY: the model may run any command this JVM's own
 * user can run. It reads and writes any file that user can reach, deletes them, opens sockets,
 * installs packages, and pushes to any remote whose credentials are on the machine. It inherits
 * this process's environment ENTIRE — every API key in it is one {@code env} away, this library's
 * own {@code RATCHET_*} settings included. Nothing is stripped, for two reasons: stripping some of
 * it would advertise a confinement this cannot provide, and the environment is load-bearing. The
 * corpus above needs {@code JAVA_HOME} set per invocation across two JDKs, and an agent that cannot
 * do that cannot do the work at all.
 *
 * <p>IT IS NOT BOUNDED IN TIME EITHER. {@code timeout_ms} is the model's to raise, and it is
 * honoured as asked, because the caller's default cannot know that this Maven build takes eight
 * minutes while the model reading the last three failures does. What stops a runaway is
 * {@code run_in_background} and the job tool that kills it, not a ceiling written by whoever
 * constructed this.
 *
 * <p>CONFINEMENT, IF IT IS WANTED, BELONGS OUTSIDE THIS CLASS AND OUTSIDE THIS JVM: a container, a
 * user with no credentials of its own, a filesystem namespace, a network policy. {@link Path} here
 * means "where commands start" and means nothing else. {@code Json}'s javadoc already notes that
 * this process may be running arbitrary code from a stranger's repository; this is the tool that
 * makes that literal, and a caller who cannot say yes to the paragraph above should not construct
 * one.
 *
 * <p>NOTHING PERSISTS BETWEEN CALLS. Every call is a fresh {@code bash -c}, so an exported
 * variable, an activated virtualenv, a started daemon's job control and a {@code cd} are all gone
 * when it returns: USE {@code workdir}, NOT {@code cd}, and pass variables inline
 * ({@code JAVA_HOME=... mvn ...}) rather than exporting them in an earlier call that no longer
 * exists. It is not a login shell either, so no profile is read and the {@code PATH} is this JVM's
 * — which is the usual reason a command that works in a terminal does not work here.
 *
 * <p>WHAT COMES BACK, IN ORDER: stdout, then a {@code [stderr]} section when anything was written
 * there, then a marker for a timeout and a marker for a signal where either applies, then
 * {@code [exit code: N]} — on every result without exception, timeouts included. That is dsh's
 * shape, so a model that has met one reads this without being told. A NON-ZERO EXIT IS A NORMAL
 * RESULT the model interprets, never an error: a failing compile is the most useful thing this tool
 * returns all day.
 *
 * <p>NOTHING HERE THROWS FOR A MISTAKE THE MODEL MADE. A missing {@code command}, a
 * {@code workdir} that is not a directory, a {@code bash} that is not on the {@code PATH}, a
 * command killed by the timeout — each is a sentence in the result saying what happened and what to
 * do next. The constructor is the only part that throws, and only for the caller's own wiring.
 */
public final class Shell {

    /**
     * HOW MUCH OF ONE STREAM IS HELD IN MEMORY, WHICH IS A HEAP BOUND AND NOT A DISPLAY BOUND.
     *
     * <p>{@link Retain#MOST} decides what the model is shown. This decides what is kept long enough
     * to be shown, and it exists because {@code yes} inside a two-minute timeout writes gigabytes:
     * a tool that shells out must not be the thing that ends the JVM. A million characters is about
     * 2 MB of heap per stream and 62 times more than any model will be told, so in every ordinary
     * case the whole of the output is here and {@code Retained}'s own notice reports the true total.
     * Past it the stream is still READ — stopping would block the child on a full pipe — and only
     * the keeping stops, with a marker naming how much went by.
     */
    private static final int KEEPING = 1_000_000;

    /** How long a killed process is given to report a status before {@link #NO_STATUS} stands in. */
    private static final long REAPING_MS = 2_000;

    /** How long a process gets to handle SIGTERM before it is killed outright. */
    private static final long GRACE_MS = 250;

    /** How long a reader thread is waited for once the process is gone. See {@link #settled}. */
    private static final long DRAINING_MS = 2_000;

    /** No number was ever reported, which is not the same as a command that exited 255. */
    private static final int NO_STATUS = -1;

    /** Where a command starts when the call names no {@code workdir}. NOT a boundary. */
    private final Path root;

    /** How long a foreground command may run when the call does not say. */
    private final Duration patience;

    /** Where a {@code run_in_background} command goes, and who owns its lifetime afterwards. */
    private final Jobs jobs;

    /** Where the rest of a result too big to send goes. {@link Spilling#none()} unless told. */
    private final Spilling spilling;

    /**
     * EVERYTHING A CALLER DECIDES ABOUT THIS TOOL: THREE THINGS, AND NONE OF THEM IS CONFINEMENT.
     *
     * @param root     the directory every command starts in unless the call names a
     *                 {@code workdir}. Read the class note before deciding it protects anything: it
     *                 is a starting point, not a boundary. It is not required to exist yet — a
     *                 workspace handed over between stages may be created after the agent is wired
     *                 — and a call that finds it missing says so rather than throwing.
     * @param patience how long a foreground command may run when the call names no
     *                 {@code timeout_ms}. It is a default and not a ceiling; the model may ask for
     *                 longer and is given it.
     * @param jobs     where a background command is handed to. Required, because a model offered
     *                 {@code run_in_background} in the schema will use it, and discovering at that
     *                 moment that there is nowhere to put it is a wiring error in the caller rather
     *                 than a mistake by the model.
     */
    public Shell(Path root, Duration patience, Jobs jobs) {
        this(root, patience, jobs, Spilling.none());
    }

    /**
     * THE SAME, TOLD WHERE THE REST OF A BIG RESULT GOES — which is the difference between a record
     * that holds the output and a record that holds a page of it.
     *
     * <p>{@link Retain#MOST} bounds what the MODEL is shown and that is right. {@link #KEEPING}
     * bounds what is held in heap and that is right too. What was wrong is that everything between
     * them — up to a million characters of a failing build — was read, held, shown as sixteen
     * thousand, and then dropped on the floor when the call returned. For {@code read} that is
     * harmless because the file is still on disk and the footer says which page you got. For
     * {@code bash} the output IS the only copy, and a build's ten thousand lines of compiler errors
     * do not exist anywhere else.
     *
     * <p>Reported by the consumer whose own rule is "bound the prompt, never the record", who took
     * the narrowing knowingly and asked that it not drift unrecorded. This is the seam that undoes
     * it: {@link Spilling#to} hands the whole text to the caller's store and puts their locator in
     * front of the model, and {@link Spilling#none} — the default — is exactly today's behaviour.
     *
     * <p>NOT WIRED INTO {@code job_output}, deliberately and for a different reason: a background
     * job's output is read incrementally across polls, so the model sees all of what the ring held
     * rather than one bounded page of it, and what overruns the ring already says so.
     */
    public Shell(Path root, Duration patience, Jobs jobs, Spilling spilling) {
        this.spilling = spilling == null ? Spilling.none() : spilling;
        if (root == null) {
            throw new IllegalArgumentException("a shell needs a directory to start commands in");
        }
        if (patience == null || patience.isNegative() || patience.isZero()) {
            throw new IllegalArgumentException("a default timeout of " + patience
                    + " would kill every command before it ran");
        }
        if (jobs == null) {
            throw new IllegalArgumentException("a shell needs somewhere to put a background job; "
                    + "the schema offers run_in_background and the model will use it");
        }
        this.root = root;
        this.patience = patience;
        this.jobs = jobs;
    }



    /**
     * ONE TOOL, NAMED {@code bash}, WHICH IS THE NAME EVERY MODEL HAS ALREADY MET.
     *
     * <p>Handed to {@code Asking} as it is, or merged with the other tools in this package. The
     * schema is built here rather than held as a constant because the default timeout is in it, and
     * a model told the wrong default writes {@code timeout_ms} on calls that never needed it.
     *
     * <p>THE ROOT IS NOT IN THE SCHEMA. A filesystem path is not escaped JSON — one quote or
     * backslash in it and the schema no longer parses — and the place an agent is told where its
     * workspace is is the system prompt, which says it once instead of on every tool.
     */
    public Map<Tool, Calling> tools() {
        return Map.of(new Tool("bash", WHAT_IT_DOES, schema()), this::ran);
    }

    /** What the model is told the tool is for. Short, because the schema carries the detail. */
    private static final String WHAT_IT_DOES = """
            Run a command with bash and read back what it printed, then its exit code. \
            A non-zero exit is an answer to interpret, not a failure to report. \
            Nothing persists between calls: each one is a new shell, so set the directory with \
            workdir rather than cd, and pass variables inline (FOO=bar cmd) rather than \
            exporting them. Anything slower than the timeout should be started with \
            run_in_background and collected with job_output.""";

    private String schema() {
        return """
                {"type":"object","properties":{\
                "command":{"type":"string",\
                "description":"The command, exactly as bash should read it."},\
                "description":{"type":"string",\
                "description":"What this command is for, in 5-10 words. Recorded, never run."},\
                "timeout_ms":{"type":"integer",\
                "description":"How long to wait before killing it. Default %d."},\
                "workdir":{"type":"string",\
                "description":"Where to run it: absolute, or relative to the workspace. Nothing \
                persists between calls, so use this instead of cd."},\
                "run_in_background":{"type":"boolean",\
                "description":"Start it, return a job id and do not wait. Use it for builds and \
                anything else slower than the timeout, then read it with job_output."}},\
                "required":["command","description"]}"""
                .formatted(patience.toMillis());
    }

    /**
     * ONE CALL, AND EVERY WAY IT CAN GO WRONG IS A SENTENCE RATHER THAN A THROW.
     *
     * <p>{@link Args#need} raises for a missing argument, which is right for a reader whose message
     * is written for the model — but a raise leaves this class with the answer half composed, and
     * {@code Asking} would hand back the exception's message with no word about what happened to
     * the command. Caught here, the same sentence goes back with "nothing ran" attached to it, and
     * that clause is the part the model acts on.
     */
    private String ran(Called call) {
        String arguments = call.arguments();
        String command;
        try {
            command = Args.need(arguments, "command");
        } catch (IllegalArgumentException missing) {
            return missing.getMessage() + " — so nothing ran. Send the command as a string in the "
                    + "command field.";
        }

        // REQUIRED IN THE SCHEMA, READ WITH maybe() ON PURPOSE. Marking it required is what makes a
        // model write it, and a model that has said what a command is for writes better commands.
        // Enforcing it here would refuse a runnable command over a field that never reaches bash:
        // it is the label a background job is filed under, and otherwise it travels only inside the
        // arguments, which the record and the watcher already keep whole.
        String label = Args.maybe(arguments, "description", command);

        Path directory;
        String where = Args.maybe(arguments, "workdir", "");
        try {
            // A path that is already absolute wins; a relative one is resolved against the root.
            // That is Path.resolve's own rule and it is the rule the schema describes.
            directory = where.isEmpty() ? root : root.resolve(where).normalize();
        } catch (InvalidPathException nonsense) {
            return "workdir is not a usable path (" + Retain.glance(where) + "): "
                    + message(nonsense) + ". Nothing ran.";
        }
        if (!Files.isDirectory(directory)) {
            return "there is no directory at " + directory + ", so nothing ran. A relative workdir "
                    + "is resolved against " + root + "; create the directory first, or name one "
                    + "that exists.";
        }

        ProcessBuilder building = new ProcessBuilder("bash", "-c", command).directory(
                directory.toFile());
        return asked(arguments, "run_in_background")
                ? handedOver(building, label, directory)
                : waitedFor(building, timeout(arguments));
    }

    /**
     * A FLAG READ TWICE, BECAUSE THE SHARED READER CANNOT SEE THE FORM THE SCHEMA ASKS FOR.
     *
     * <p>{@code Json.read} stops at anything that does not open with a quote — deliberately, since
     * in a tool argument a bare word is usually a mistake — and {@link Args#flag} is built on it. A
     * JSON boolean opens with a bare {@code t}. The schema here says {@code "type":"boolean"}, so a
     * model writes {@code "run_in_background": true} unquoted, and that reads as FALSE: measured
     * against the shipped reader, {@code {"run_in_background":true}} is false while
     * {@code {"run_in_background":"true"}} is true. The background path was reachable only from the
     * one form the schema does not invite, which is a tool advertising a mode it cannot enter.
     *
     * <p>THIS IS {@code Json.number}'S CARVE-OUT AGAIN, ONE TYPE LATER. That method exists because
     * the same rule "silently swallowed every integer argument any agent ever sent". THE FIX
     * BELONGS IN {@link Args} BESIDE IT AND NOT HERE, and the evidence for that is in this module
     * already: {@code Workspace} met the identical defect on {@code replace_all} and wrote the same
     * two-line stand-in. Two private copies of one carve-out, found independently, is what a
     * missing method in a shared reader looks like from the outside.
     *
     * <p>{@link Args#flag} IS STILL ASKED FIRST, so a model that quotes its booleans is answered by
     * the shared reader and this never runs. {@code Json.part} is what reads the bare scalar,
     * because it is the one scanner in the library that hands back a raw value whatever shape it is.
     */
    private static boolean asked(String arguments, String name) {
        return Args.flag(arguments, name)
                || "true".equalsIgnoreCase(Json.part(arguments, name).trim());
    }

    /**
     * A JOB, WHICH IS 3.9% OF CALLS AND GATES EVERYTHING ABOVE IT.
     *
     * <p>32 calls to {@code job_output} in the measured corpus, and each one stands for a Maven or
     * Gradle build the agent was able to wait for. Without this an agent either blocks a
     * synchronous tool past its timeout or does not run the build at all — and the build is the
     * only thing that tells it whether what it just wrote was right.
     *
     * <p>The result says the id, says that no output is here, and says which tool reads it. A model
     * told only "started as job 7" asks {@code bash} for the output next, because nothing pointed
     * it anywhere else.
     */
    private String handedOver(ProcessBuilder building, String label, Path directory) {
        String id;
        try {
            id = jobs.start(label, building);
        } catch (RuntimeException notStarted) {
            return "the background job did not start: " + message(notStarted) + ". The command has "
                    + "not run. Try it in the foreground, or fix what stopped it and start it "
                    + "again.";
        }
        return "started in the background as job " + id + ", running in " + directory
                + ". Nothing of its output is here: this call did not wait, and the command may not "
                + "have printed anything yet. Read it with job_output on job " + id
                + ", and stop it with job_kill. timeout_ms does not apply to a background job — the "
                + "job owns its own lifetime.";
    }

    /**
     * THE FOREGROUND PATH: start it, drain both streams, wait, and report whatever exists.
     *
     * <p>STDIN IS CLOSED BEFORE ANYTHING ELSE. The child otherwise holds a pipe nobody will ever
     * write to, so a command that prompts — {@code git commit} with no message, an installer asking
     * to continue, {@code ssh} wanting a passphrase — burns the entire timeout waiting for input
     * that cannot arrive, and returns a killed process and no explanation. Closed, it reads EOF and
     * fails in the first second with a message the model can act on.
     *
     * <p>BOTH STREAMS ARE DRAINED BY THEIR OWN THREADS, WHICH IS NOT A STYLE CHOICE. A pipe holds
     * about 64 KB; a child that fills one and finds nobody reading blocks forever, and reading
     * stdout to the end before touching stderr is exactly how that deadlock is written. Merging the
     * two with {@code redirectErrorStream} would avoid the second thread and lose the
     * {@code [stderr]} section that makes a build's one useful line findable in its forty thousand.
     */
    private String waitedFor(ProcessBuilder building, long millis) {
        Process process;
        try {
            process = building.start();
        } catch (IOException notStarted) {
            return "bash did not start: " + message(notStarted) + ". Nothing ran. bash has to be "
                    + "on the PATH of the process ratchet itself is running in.";
        }
        eof(process);
        Drain out = new Drain(process.getInputStream());
        Drain err = new Drain(process.getErrorStream());
        Thread reading = started("bash-stdout", out);
        Thread complaining = started("bash-stderr", err);

        boolean ended;
        try {
            ended = process.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // The interrupt belongs to whoever set it and is put straight back. The command is
            // killed rather than left running against a lane that is being torn down.
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return text(out, err, status(process),
                    "[the thread running this call was interrupted, so the command was killed. "
                            + "What is above is what it had printed by then.]",
                    abandoned(settled(reading, complaining)));
        }
        if (ended) {
            return text(out, err, process.exitValue(), null,
                    abandoned(settled(reading, complaining)));
        }
        // A COMMAND THAT FINISHED ON THE DEADLINE DID NOT TIME OUT. The process can end between
        // waitFor giving up and the kill being sent, and reporting that as "timed out and was
        // killed" next to [exit code: 0] hands the model a result that contradicts itself — over a
        // build that succeeded, which is the worst call in the run to be wrong about.
        boolean killed = stopped(process);
        int code = status(process);
        return text(out, err, code, killed ? "[timed out after " + millis + " ms and was killed. "
                        + "What is above is what it had printed by then. If it needs longer, run "
                        + "it again with run_in_background and collect it with job_output.]" : null,
                abandoned(settled(reading, complaining)));
    }

    /**
     * WHAT THE MODEL ASKED FOR, OR THE CALLER'S DEFAULT.
     *
     * <p>Floored at 1 ms because {@link Process#waitFor} treats zero and negatives as "do not wait"
     * and would return a killed process for every call the model wrote {@code "timeout_ms": 0} on.
     * The default is narrowed to an {@code int} for {@link Args#number}, which costs nothing: the
     * clamp is 24 days.
     */
    private long timeout(String arguments) {
        int fallback = (int) Math.min(Integer.MAX_VALUE, patience.toMillis());
        return Math.max(1L, Args.number(arguments, "timeout_ms", fallback));
    }

    /**
     * THE RESULT, IN dsh'S ORDER, WITH THE EXIT CODE ON EVERY PATH THROUGH THIS METHOD.
     *
     * <p>THE MARKERS ARE APPENDED AFTER THE BOUND RATHER THAN INSIDE IT. Each stream is bounded on
     * its own by {@link Retain#most}, and the markers are added to what comes back, so
     * {@code [exit code: N]} cannot be the thing a truncation removes — which is what bounding the
     * assembled text would do, on exactly the runs where the code matters most.
     *
     * <p>TWO BOUNDS RATHER THAN ONE, FOR THE SAME REASON THE STREAMS ARE KEPT APART. A failing
     * Maven build prints tens of thousands of lines of progress to stdout and the reason on stderr.
     * One bound over the pair spends all of it on the progress and cuts the sentence that says what
     * went wrong. The cost is a worst case of twice {@link Retain#MOST} plus the markers, which is
     * the right trade for never losing the half that explains the other half.
     *
     * @param notes what happened to the process and to the reading of it, when anything did: a
     *              timeout, an interrupt, output left in a pipe nobody could close. Nulls are
     *              skipped, so the ordinary result carries none of them.
     */
    private String text(Drain out, Drain err, int code, String... notes) {
        StringBuilder result = new StringBuilder();
        section(result, out, "stdout", null);
        section(result, err, "stderr", "[stderr]");
        if (result.length() == 0) {
            // A bare exit code reads as a truncated result. The commonest silent success — mkdir,
            // cp, a test runner told to be quiet — is exactly where a model otherwise runs the
            // command a second time to find out whether anything happened.
            result.append("[no output]");
        }
        for (String note : notes) {
            if (note != null) {
                result.append('\n').append(note);
            }
        }
        String signal = signalled(code);
        if (signal != null) {
            result.append('\n').append(signal);
        }
        if (code == NO_STATUS) {
            result.append("\n[no exit status: the process was still alive ").append(REAPING_MS)
                    .append(" ms after being killed, so -1 stands in for a number the operating "
                            + "system never gave]");
        }
        return result.append("\n[exit code: ").append(code).append(']').toString();
    }

    /**
     * ONE STREAM'S TEXT, BOUNDED, WITH ITS OWN MARKER WHEN MORE WENT PAST THAN WAS KEPT.
     *
     * <p>The trailing newline nearly every command ends with is stripped, so {@code [exit code: N]}
     * sits under the last line of output rather than under a blank one.
     *
     * <p>IN THE RUNAWAY CASE THE READER SEES TWO NOTICES, AND BOTH ARE TRUE OF DIFFERENT BOUNDS.
     * {@code seq 1 200000} measured here comes back as {@code ... (truncated, total 1000000 chars)}
     * from {@link Retain#most}, which is honest about the string it was handed, followed by
     * {@code [stdout: stopped keeping after 1000000 characters, of 1288895 printed]}, which is the
     * only line that knows what the command actually printed. Folding the second into the first
     * would mean writing {@code Retained}'s sentence by hand with a number it never saw, and this
     * library spent a version removing the six hand-written copies of that sentence.
     */
    private void section(StringBuilder into, Drain drain, String stream, String header) {
        String kept = spilling.kept(drain.kept(), Retain.MOST).stripTrailing();
        if (kept.isEmpty()) {
            return;
        }
        if (into.length() > 0) {
            into.append('\n');
        }
        if (header != null) {
            into.append(header).append('\n');
        }
        into.append(kept);
        if (drain.overflowed()) {
            into.append("\n[").append(stream).append(": stopped keeping after ").append(KEEPING)
                    .append(" characters, of ").append(drain.seen()).append(" printed]");
        }
    }

    /**
     * A SIGNAL, READ OUT OF THE EXIT CODE, BECAUSE 137 ON ITS OWN TEACHES NOTHING.
     *
     * <p>A Maven build the kernel's out-of-memory killer takes returns 137 and says nothing about
     * why; naming SIGKILL is the difference between a model raising the heap and running the same
     * command again. The convention is the shell's own — 128 plus the signal number — and this
     * reads it as bash reports it.
     *
     * <p>A COMMAND THAT EXITS 137 OF ITS OWN ACCORD IS INDISTINGUISHABLE FROM ONE THAT WAS KILLED,
     * and that is a property of the convention rather than of this code. Saying "killed by signal
     * 9" about a deliberate {@code exit 137} is a smaller error than staying silent about every
     * process the kernel actually killed.
     */
    private static String signalled(int code) {
        if (code <= 128 || code > 128 + 64) {
            return null;
        }
        int number = code - 128;
        String name = switch (number) {
            case 1 -> " (SIGHUP)";
            case 2 -> " (SIGINT)";
            case 3 -> " (SIGQUIT)";
            case 6 -> " (SIGABRT)";
            case 8 -> " (SIGFPE)";
            case 9 -> " (SIGKILL, which is usually the out-of-memory killer)";
            case 11 -> " (SIGSEGV)";
            case 13 -> " (SIGPIPE)";
            case 15 -> " (SIGTERM)";
            default -> "";
        };
        return "[killed by signal " + number + name + "]";
    }

    /**
     * TERM FIRST, KILL A QUARTER OF A SECOND LATER.
     *
     * <p>A process that handles TERM removes its lock file, closes its database and finishes
     * writing the log the model is about to read. One that ignores it is killed anyway, so the
     * whole cost of asking politely is 250 ms on a call that has already spent its entire timeout.
     *
     * <p>NEITHER SIGNAL REACHES THE GRANDCHILDREN. This kills the {@code bash} process; anything it
     * forked and left running is still running afterwards, because reaping a process tree needs a
     * process group and a platform assumption this class does not make. {@link #settled} is written
     * around that fact rather than around a promise to fix it.
     *
     * @return whether there was still something alive to kill, which is what decides whether the
     *         result says it timed out
     */
    private static boolean stopped(Process process) {
        if (!process.isAlive()) {
            return false;
        }
        process.destroy();
        try {
            if (process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        process.destroyForcibly();
        return true;
    }

    /** The exit code of a process that has been killed, or {@link #NO_STATUS} if it never says. */
    private static int status(Process process) {
        try {
            if (process.waitFor(REAPING_MS, TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return NO_STATUS;
    }

    /**
     * THE READERS ARE WAITED FOR, AND THE WAIT IS BOUNDED, BECAUSE THE PIPE OUTLIVES THE SHELL.
     *
     * <p>{@code bash -c "sleep 30 &"} exits in a millisecond and leaves a grandchild holding the
     * write end of stdout, so {@code read} on that stream returns nothing and does not end either.
     * An unbounded join would hang the agent's lane on a command that had already finished, and
     * {@link #stopped} cannot help: killing bash does not touch what bash forked.
     *
     * <p>ONE DEADLINE FOR BOTH READERS, NOT ONE EACH. Measured on that command: a bound applied per
     * reader charges it twice, and the call took 4,016 ms to report a command that ended
     * immediately. Shared, the worst case is {@value #DRAINING_MS} ms, and it is paid only when
     * something really is still holding the pipe — a reader whose stream has closed returns from
     * {@code join} at once, which is every ordinary command.
     *
     * <p>The readers are daemon threads, so an abandoned one cannot hold the JVM open; it costs a
     * thread and the characters it has kept. That is the honest price of not owning a process tree
     * this class did not create, and {@link #abandoned} tells the model when it has been paid.
     *
     * @return whether both readers reached the end of their stream
     */
    private static boolean settled(Thread... readers) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAINING_MS);
        for (Thread reader : readers) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            try {
                reader.join(Math.max(1L, left));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (reader.isAlive()) {
                return false;
            }
        }
        return true;
    }

    /**
     * WHY THE RESULT MAY BE SHORT, WHEN IT IS.
     *
     * <p>Silence here would be a result that looks complete and is not. A model that ran
     * {@code make -j8 &} and got back three lines and an exit code has no way to tell that the
     * build is still writing to a pipe nobody is reading any more; told, it knows to look at the
     * log file instead, or to start the work as a job in the first place.
     */
    private static String abandoned(boolean drained) {
        return drained ? null : "[something the command left running still holds its output stream, "
                + "so reading was let go after " + DRAINING_MS + " ms. Anything printed from here "
                + "on is not in this result; start work that outlives the call with "
                + "run_in_background instead.]";
    }

    private static Thread started(String name, Drain drain) {
        Thread thread = new Thread(drain, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** EOF on the child's stdin, so a command that prompts fails now instead of at the timeout. */
    private static void eof(Process process) {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.flush();
        } catch (IOException closing) {
            // The child is already gone, which is a thing that has happened rather than a failure
            // of this call: whatever it printed first is still worth reporting.
        }
    }

    /** What went wrong, in words, because a bare class name tells the model nothing. */
    private static String message(Throwable threw) {
        return threw.getMessage() == null ? threw.getClass().getSimpleName() : threw.getMessage();
    }

    /**
     * ONE STREAM, READ TO THE END WHETHER OR NOT ANY OF IT IS BEING KEPT.
     *
     * <p>THE READING NEVER STOPS EARLY, AND THAT IS THE WHOLE DESIGN. Stopping at the keeping bound
     * would leave the pipe to fill, and a child blocked writing to a full pipe is a command that
     * never finishes — a timeout reported against a program that had already done its work. So
     * every character is read; past {@link #KEEPING} they are counted and dropped, which turns an
     * unbounded heap cost into a number the result can quote.
     *
     * <p>The counters are synchronised because a bounded join means the main thread may read them
     * while this one is still running. UTF-8 is decoded across chunk boundaries by
     * {@link InputStreamReader}, so a multibyte character split by a read is not corrupted.
     */
    private static final class Drain implements Runnable {

        private final InputStream from;
        private final StringBuilder kept = new StringBuilder();
        private long seen;

        Drain(InputStream from) {
            this.from = from;
        }

        @Override
        public void run() {
            char[] buffer = new char[8_192];
            try (Reader reader = new InputStreamReader(from, StandardCharsets.UTF_8)) {
                for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
                    took(buffer, read);
                }
            } catch (IOException closed) {
                // A pipe torn down under the reader is what a killed process looks like from here,
                // not a failure to report. What was read before it is still the best answer there
                // is, and it is already in the buffer.
            }
        }

        private synchronized void took(char[] buffer, int read) {
            seen += read;
            int room = KEEPING - kept.length();
            if (room > 0) {
                kept.append(buffer, 0, Math.min(room, read));
            }
        }

        synchronized String kept() {
            return kept.toString();
        }

        synchronized long seen() {
            return seen;
        }

        /** Whether more went past than was kept, which is the only thing the marker needs to know. */
        synchronized boolean overflowed() {
            return seen > kept.length();
        }
    }
}