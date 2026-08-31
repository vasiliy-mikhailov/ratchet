package tech.mikhailov.ratchet.tools;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import tech.mikhailov.ratchet.llm.Called;
import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Now;
import tech.mikhailov.ratchet.llm.Tool;
import tech.mikhailov.ratchet.record.Json;

/**
 * WORK THAT OUTLIVES THE TOOL CALL THAT STARTED IT, which is the only way a real build is ever run.
 *
 * <p>THREE POINT NINE PER CENT OF CALLS, AND IT GATES EVERYTHING. TOOLS.md counts 816 tool calls
 * across six runs of one task: {@code job_output} is 32 of them, present in 4 of the 6 runs, which
 * reads as rounding error until you ask what one of those 32 stands for. A Maven or Gradle build
 * over a real corpus runs for minutes. A tool that runs it synchronously either blocks past its own
 * timeout or is never called at all — and the build is the ONLY thing in the loop that says whether
 * the edit was right, so an agent without this is an agent guessing. Thirty-two calls are
 * thirty-two builds the agent was able to wait for. {@code job_kill} was used once, on a build that
 * hung; one use is the whole argument for shipping it, because the run that needed it had no other
 * way out.
 *
 * <p>THE DRAIN THREAD IS A DAEMON AND THIS PROJECT HAS ALREADY PAID TO LEARN THAT. {@code Wire}
 * reads a response body on a thread named {@code ratchet-wire-reader} for the same reason this
 * class reads a pipe on one: the thread blocks inside the JDK on something that may never produce
 * again, so it outlives the call that started it. Non-daemon, that is a JVM which never exits — a
 * nightly sweep that finished its work at 04:00 and a build that hangs after the last test passed.
 * Two test files there described the reader as a daemon in prose while nothing asserted it, and the
 * {@code setDaemon} call survived being deleted. These threads are named {@code ratchet-job-<id>}
 * so the same assertion can be written against them.
 *
 * <p>THE OTHER HALF OF THAT BARGAIN IS A SHUTDOWN HOOK. A daemon reader lets the JVM exit while a
 * job is still running, and a child process is NOT killed when its parent JVM exits: the sweep ends
 * and leaves a Maven build burning a core and holding the locks in {@code ~/.m2} until somebody
 * logs in and notices. One hook is registered, on the first {@link #start}, and it signals whatever
 * is still alive.
 *
 * <p>{@code job_output} CONSUMES WHAT IT RETURNS, AND THAT IS THE FEATURE. A model watching a
 * six-minute build polls; if each poll returned the whole log from the top, every poll would cost
 * more than the last one, the new lines would be at the bottom behind everything already read, and
 * the model would pay for the prefix again on every turn of a conversation that is already growing.
 * Here each call returns what has arrived SINCE THE LAST CALL for that job, in at most
 * {@link Retain#MOST} characters, and says how much is still queued behind it.
 *
 * <p>WHAT IS BOUNDED, WITH THE NUMBERS. Each job holds at most 64,000 characters not yet read —
 * four reads' worth, so a model polling once a minute against a chatty build loses nothing — and
 * drops the OLDEST characters when it overflows, because the line that says why a build failed is
 * the last one, never the first. The registry keeps 32 jobs and evicts the oldest ENDED one to make
 * room; a running job is never evicted. That is 2,048,000 characters, a couple of megabytes, and it
 * is the whole memory this class can ever hold. An evicted id answers {@code gone}.
 *
 * <p>THE STATUS LINE IS A CLOSED SET OF FOUR AND IT IS ALWAYS LAST. {@code running},
 * {@code exited N}, {@code killed}, {@code gone} — a model that matches on the end of the response
 * always finds one, including when it named no job at all, which is why an unusable {@code job_id}
 * answers {@code gone} rather than an error with no status on it. The bound is applied to the
 * output and the line appended after it, because a response whose status line was the part
 * truncated away is exactly the response this contract exists to prevent.
 *
 * <p>NOTHING HERE THROWS AT A MODEL. A missing id, an unknown id, a job already dead, a process
 * that would not start — each is a sentence the model can act on with a status after it.
 * {@link #start} throws, and only at its caller: it is wired by {@code Shell} and a builder whose
 * output does not arrive on a pipe is a programming error, not a model's.
 */
public final class Jobs {

    /**
     * HOW MUCH UNREAD OUTPUT ONE JOB HOLDS: four {@code job_output} reads' worth.
     *
     * <p>Not one, which is the obvious choice and the wrong one. A model that starts a build and
     * then reads three files before polling has produced three turns of silence, and at one read's
     * worth the compile errors it went to look for would have been dropped in favour of Maven's
     * download progress. Four is the slack that costs 64,000 characters per job and buys a poll
     * that can be several turns late.
     */
    static final int KEPT = 4 * Retain.MOST;

    /** How many jobs stay readable. Beyond this the oldest ENDED job is forgotten, never a live one. */
    private static final int REMEMBERED = 32;

    /** What {@code wait} costs when the model asks to wait and does not say for how long. */
    private static final long WAITED = 30_000;

    /**
     * THE CEILING ON ONE BLOCKING READ, BECAUSE THE LANE IS SINGLE-FILE. {@code Asking} runs tools
     * sequentially on the calling thread, so a blocked {@code job_output} is the whole agent
     * blocked. Five minutes is long enough to hold a build to its end and short enough that a
     * wedged job cannot hold the lane the way {@code Watch#ceiling} exists to stop a wedged stream
     * holding it.
     */
    private static final long MOST_WAITED = 300_000;

    /** How often a blocked read wakes to check its own deadline. It is a timeout on nothing. */
    private static final long TICK = 100;

    /** How long a signalled process is given to go before it is forced, and again after forcing. */
    private static final long GRACE = 2_000;

    /** How long {@code job_kill} waits for the drain thread to file the exit code it caused. */
    private static final long SETTLING = 1_000;

    /**
     * A SHELL IS NOT A KIND. {@code Shell} runs a model's command as {@code bash -lc "mvn -q
     * install"}, so every job in the list would read {@code [bash]} and the list would say nothing.
     */
    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "dash", "ksh", "fish",
            "cmd", "cmd.exe", "powershell", "pwsh");

    /** Words that stand in front of the program without being it. */
    private static final Set<String> BEFORE = Set.of("cd", "export", "time", "nohup", "sudo",
            "env", "exec", "source", ".");

    private final Now now;

    /**
     * Insertion-ordered, so eviction takes the oldest and {@code job_list} reads in the order the
     * jobs were started. Guarded by itself, and the registry lock is NEVER taken while a job's own
     * monitor is held — every path here goes registry first, job second, so the two cannot deadlock.
     */
    private final Map<String, Job> registry = new LinkedHashMap<>();

    private int started;

    private boolean sweeping;

    public Jobs() {
        this(Now.SYSTEM);
    }

    /**
     * THE CLOCK, HANDED IN, AND PUBLIC — which is the point of it existing.
     *
     * <p>Six times in ratchet-llm a value was made injectable for its own tests and left
     * unreachable from outside, and every one of those was reported by a consumer rather than found
     * at home. Everything this class reports about time — how long a build has run, when a blocking
     * read gives up — is read through here, so a consumer can assert a three-minute build in
     * microseconds. The one thing it does not fake is the sleeping itself: {@code Object#wait}
     * measures against the machine, so a blocked read wakes on {@link #TICK} and asks this clock
     * whether its deadline has passed, exactly as {@code Wire} does with its stall.
     */
    public Jobs(Now now) {
        this.now = now == null ? Now.SYSTEM : now;
    }

    /**
     * START A PROCESS IN THE BACKGROUND AND RETURN THE ID THE MODEL WILL POLL. For {@code Shell}.
     *
     * <p>THIS CLASS OWNS THE PROCESS'S THREE STREAMS AND EACH FOR A REPORTED REASON. Standard error
     * is merged into standard output, because a build writes its progress to one and its failure to
     * the other and a model handed them apart cannot tell which line came before which — the
     * ordering IS the diagnosis. Standard input is closed as soon as the process starts, because a
     * background job has nobody to type at it: a {@code git} asking for a password or a Maven plugin
     * asking to confirm sits on an open pipe for ever and looks exactly like a slow build. And the
     * output must arrive on a PIPE, which is the one thing here that throws: a builder already
     * redirected to a file has nothing for {@code job_output} to read, so the model would be told
     * an eleven-minute build printed nothing.
     *
     * <p>A COMMAND THAT WILL NOT START IS A JOB, NOT AN EXCEPTION. The command came from the model —
     * a wrapper script that is not there, a directory that is not — so the failure is the model's
     * to fix and belongs where it looks: the returned id names a job that has already ended, whose
     * output is the sentence saying what happened. It exits 127 because that is what a shell reports
     * for a command it could not run, and inventing a fifth status would break a closed set of four
     * that a model matches against.
     *
     * @param label what to call this job in {@code job_list}; the command itself when blank
     * @param pb    the process to start, whose output must still be on a pipe
     * @return a short id, {@code j1}, {@code j2}, ... — short because the model retypes it on every
     *         poll, and 32 polls of a long build are 32 chances to mistype something longer
     */
    public String start(String label, ProcessBuilder pb) {
        if (pb == null) {
            throw new IllegalArgumentException("a job needs a ProcessBuilder and none was handed in");
        }
        if (!ProcessBuilder.Redirect.PIPE.equals(pb.redirectOutput())) {
            throw new IllegalArgumentException("this job's output was redirected to "
                    + pb.redirectOutput() + " before it was started, so job_output would have "
                    + "nothing to read and the model would be told the build printed nothing");
        }
        pb.redirectErrorStream(true);

        String named = label == null || label.isBlank() ? String.join(" ", pb.command())
                : label.strip();
        String kind = kind(pb.command());
        String id;
        synchronized (registry) {
            id = "j" + (++started);
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException nostart) {
            Job stillborn = new Job(id, kind, named, null, now.millis());
            stillborn.finished(127, now.millis());
            stillborn.wrote("the process never started: " + nostart.getMessage()
                    + ". Nothing ran, so there is no output to wait for.");
            keep(stillborn);
            return id;
        }
        try {
            process.getOutputStream().close();
        } catch (IOException shut) {
            // Already closed, which is the only way this fails and is the state we wanted.
        }

        Job job = new Job(id, kind, named, process, now.millis());
        keep(job);
        sweepOnExit();
        Thread reader = new Thread(() -> drain(job), "ratchet-job-" + id);
        reader.setDaemon(true);
        reader.start();
        return id;
    }

    /**
     * The three tools, in the order a run uses them, as a fresh map each call.
     *
     * <p>Fresh because {@code Asking} copies what it is given at construction and keys it by the
     * whole {@link Tool} record: a shared mutable map would let one agent's wiring reach into
     * another's, for no gain over three {@code put} calls.
     */
    public Map<Tool, Calling> tools() {
        Map<Tool, Calling> offered = new LinkedHashMap<>();
        offered.put(new Tool("job_output", OUTPUT_SAYS, OUTPUT_TAKES), this::output);
        offered.put(Tool.of("job_list", LIST_SAYS), this::list);
        offered.put(new Tool("job_kill", KILL_SAYS, KILL_TAKES), this::kill);
        return offered;
    }

    private static final String OUTPUT_SAYS = "Read what a background job has printed SINCE THE "
            + "LAST job_output call for that job, so polling a long build shows progress instead of "
            + "the same beginning again. Returns at once by default; pass wait=true to block until "
            + "there is something to read or the job ends. Every answer ends with a line reading "
            + "[status: running], [status: exited N], [status: killed] or [status: gone], and gone "
            + "means no job of that name is held any more.";

    private static final String OUTPUT_TAKES = "{\"type\":\"object\",\"properties\":{"
            + "\"job_id\":{\"type\":\"string\",\"description\":"
            + "\"The id the job was started with, such as j3.\"},"
            + "\"wait\":{\"type\":\"boolean\",\"description\":"
            + "\"Block until the job prints something or ends. Default false.\"},"
            + "\"timeout_ms\":{\"type\":\"integer\",\"description\":"
            + "\"How long to block for, in milliseconds. Default 30000, capped at 300000. "
            + "Giving it implies wait.\"}},"
            + "\"required\":[\"job_id\"]}";

    private static final String LIST_SAYS = "Every background job this run still holds, one per "
            + "line as \"<id> [<kind>] <status> — <label>\". Use it when a job id has been lost, or "
            + "to see what is still running before starting something else.";

    private static final String KILL_SAYS = "Ask a background job to stop. The process and every "
            + "process it started are signalled, then forced if they do not go. A job that has "
            + "already ended is not signalled. This never consumes output: whatever job_output has "
            + "not read is still there to read afterwards.";

    private static final String KILL_TAKES = "{\"type\":\"object\",\"properties\":{"
            + "\"job_id\":{\"type\":\"string\",\"description\":"
            + "\"The id of the job to stop, such as j3.\"},"
            + "\"reason\":{\"type\":\"string\",\"description\":"
            + "\"Why it is being stopped. Recorded in the answer; it changes nothing.\"}},"
            + "\"required\":[\"job_id\"]}";

    /** What has arrived since the last read, bounded, with the queue depth and the status after it. */
    private String output(Called call) {
        String json = call.arguments();
        String want;
        try {
            want = Args.need(json, "job_id");
        } catch (IllegalArgumentException missing) {
            return missing.getMessage() + ". Call job_list for the ids." + says("gone");
        }
        Job job = find(want);
        if (job == null) {
            return "No job is called " + Retain.glance(want.strip()) + ". " + held() + says("gone");
        }

        int asked = Args.number(json, "timeout_ms", -1);
        boolean blocking = flag(json, "wait") || asked > 0;
        long patience = !blocking ? 0 : Math.min(asked < 0 ? WAITED : asked, MOST_WAITED);
        if (patience > 0) {
            job.awaitAnything(now, patience);
        }

        long forgotten = job.forgotten();
        String fresh = job.take(Retain.MOST);
        int queued = job.waiting();
        String status = job.status();

        StringBuilder said = new StringBuilder(headline(job, fresh, blocking, patience));
        if (forgotten > 0) {
            said.append("\n[").append(forgotten).append(" characters printed before this were "
                    + "dropped unread: the job got more than ").append(KEPT)
                    .append(" characters ahead of job_output. Poll more often, or send the output "
                            + "to a file and read that.]");
        }
        if (!fresh.isEmpty()) {
            // take() has already bounded this at the same number; the second bound is the rule
            // every tool in this package follows and the guard if take() is ever loosened.
            said.append("\n").append(Retain.most(fresh));
        }
        if (queued > 0) {
            said.append("\n[").append(queued).append(" more characters are already waiting; call "
                    + "job_output again to read them.]");
        }
        return said + says(status);
    }

    /**
     * The sentence before the output, which has to name the job, its state and its age, because a
     * model reading this may have started three builds and be looking at any of them.
     */
    private String headline(Job job, String fresh, boolean blocking, long patience) {
        String named = job.id + " [" + job.kind + "] " + Retain.glance(job.label);
        String age = spent(job.ran(now));
        if (job.ended()) {
            if (fresh.isEmpty()) {
                return named + (job.produced() == 0
                        ? " ended after " + age + " and printed nothing at all."
                        : " ended after " + age + "; job_output has already read all "
                                + job.produced() + " characters it printed.");
            }
            return named + " ended after " + age + ". " + fresh.length()
                    + " characters, unread until now:";
        }
        if (!fresh.isEmpty()) {
            return named + " is still running after " + age + ". " + fresh.length()
                    + " characters since the last read:";
        }
        if (blocking) {
            return named + " printed nothing in the " + patience + "ms this call waited. It has "
                    + "been running for " + age + " and has printed " + job.produced()
                    + " characters in all.";
        }
        return named + " has printed nothing new since the last read and has been running for "
                + age + ". Pass wait=true with timeout_ms to block until it prints or ends.";
    }

    /** Every job still held, in the order they were started. */
    private String list(Called call) {
        List<Job> all;
        synchronized (registry) {
            all = new ArrayList<>(registry.values());
        }
        if (all.isEmpty()) {
            return "No background job has been started in this run.";
        }
        long running = all.stream().filter(job -> !job.ended()).count();
        List<String> unread = all.stream().filter(job -> job.waiting() > 0)
                .map(job -> job.id).toList();
        StringBuilder said = new StringBuilder(all.size() + (all.size() == 1 ? " job, " : " jobs, ")
                + running + " still running.");
        if (!unread.isEmpty()) {
            // NAMED, BUT NOT ALL THIRTY-TWO OF THEM. At the cap this sentence was 31 ids long and
            // buried the one number a model came here for; six is enough to act on and the rest are
            // one line further down anyway.
            String some = String.join(", ", unread.subList(0, Math.min(6, unread.size())));
            said.append(" ").append(some)
                    .append(unread.size() > 6 ? " and " + (unread.size() - 6) + " more have" : "")
                    .append(unread.size() > 6 ? "" : unread.size() == 1 ? " has" : " have")
                    .append(" output nobody has read: job_output takes it.");
        }
        for (Job job : all) {
            said.append("\n").append(job.id).append(" [").append(job.kind).append("] ")
                    .append(job.status()).append(" — ").append(Retain.glance(job.label));
        }
        return Retain.most(said.toString());
    }

    /**
     * SIGNAL A JOB, AND SHOW WHAT IT WAS DOING WITHOUT SPENDING IT.
     *
     * <p>Killing is a diagnosis: the model is stopping this because it went wrong, and the next
     * thing it wants is the reason. A kill that consumed the unread output would destroy the
     * evidence it was called to collect, so the snapshot here is a look — {@code job_output} still
     * has every character afterwards.
     */
    private String kill(Called call) {
        String json = call.arguments();
        String want;
        try {
            want = Args.need(json, "job_id");
        } catch (IllegalArgumentException missing) {
            return missing.getMessage() + ". Call job_list for the ids." + says("gone");
        }
        Job job = find(want);
        if (job == null) {
            return "No job is called " + Retain.glance(want.strip()) + ", so nothing was signalled. "
                    + held() + says("gone");
        }
        String why = Args.maybe(json, "reason", "");
        String named = job.id + " [" + job.kind + "] " + Retain.glance(job.label);
        String because = why.isEmpty() ? "" : " Reason given: " + Retain.glance(why) + ".";

        if (job.ended()) {
            return named + " had already ended after " + spent(job.ran(now))
                    + ", so nothing was signalled." + because + " " + snapshot(job)
                    + says(job.status());
        }

        job.asked();
        end(job);
        job.awaitEnd(now, SETTLING);
        String went = job.ended()
                ? named + " was signalled and has stopped, after " + spent(job.ran(now)) + "."
                : named + " was signalled and then forced, and was STILL alive " + spent(GRACE * 2)
                        + " later — it is probably stuck in the kernel. It is running for now.";
        return went + because + " " + snapshot(job) + says(job.status());
    }

    /**
     * SIGNAL THE CHILDREN BEFORE THE PARENT DIES, or half the build survives it.
     *
     * <p>A job is {@code bash -lc "mvn ..."}: destroying the process destroys the SHELL, and Maven
     * — with its forked test JVMs under it — is a child that reparents to init and carries on
     * holding the locks in {@code ~/.m2} that the next build will wait on. The handles must be
     * collected first, because once the parent is gone there is nothing left to ask for them.
     */
    private void end(Job job) {
        if (job.process == null) {
            return;
        }
        List<ProcessHandle> under = job.process.descendants().toList();
        job.process.destroy();
        under.forEach(ProcessHandle::destroy);
        if (gone(job.process, GRACE)) {
            return;
        }
        // A GRACEFUL SIGNAL FIRST AND ONLY THEN A FATAL ONE. Maven flushes its reactor summary on
        // the way out, which is the part that says which module failed; killing it outright throws
        // away the answer along with the process.
        job.process.destroyForcibly();
        under.forEach(ProcessHandle::destroyForcibly);
        gone(job.process, GRACE);
    }

    private boolean gone(Process process, long millis) {
        try {
            return process.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException stopping) {
            // The flag goes back where it was found: a thread that swallows it has silently
            // declined to be cancelled, and the next blocking call it makes does not see it.
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /** A look at what is queued, tail first, taking nothing. */
    private String snapshot(Job job) {
        int queued = job.waiting();
        if (queued == 0) {
            return "job_output has already read everything it printed.";
        }
        String tail = job.glimpse(Retain.MOST);
        String how = tail.length() < queued
                ? "The last " + tail.length() + " of the " + queued + " characters job_output has "
                        + "not read (this look consumes none of them):"
                : "The " + queued + " characters job_output has not read (this look consumes none "
                        + "of them):";
        return how + "\n" + Retain.most(tail);
    }

    /** Read on the drain thread, in characters rather than lines, and never on the caller's. */
    private void drain(Job job) {
        char[] chunk = new char[8192];
        // A READER AND NOT A readLine() LOOP. Maven's downloads and Gradle's status line are
        // written WITHOUT a newline for seconds at a time, and a line-oriented reader holds them
        // until the line ends — so a model polling a build that is working sees nothing and
        // concludes it has hung. Characters also mean a chunk boundary through a multi-byte
        // character is the decoder's problem rather than a replacement mark in the middle of a log.
        try (Reader from = new InputStreamReader(job.process.getInputStream(),
                StandardCharsets.UTF_8)) {
            int read;
            while ((read = from.read(chunk)) >= 0) {
                if (read > 0) {
                    job.wrote(new String(chunk, 0, read));
                }
            }
        } catch (IOException broke) {
            job.wrote("\n[the output stream ended early: " + broke.getMessage() + "]\n");
        }
        try {
            job.finished(job.process.waitFor(), now.millis());
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            job.wrote("\n[the reader watching this job was interrupted, so its exit code was never "
                    + "collected and the status below stays running]\n");
        }
    }

    /**
     * A BOOLEAN THE MODEL WROTE THE WAY THE SCHEMA ASKED FOR IT.
     *
     * <p>{@code Args.flag} goes through {@code Json.read}, which stops at anything that does not
     * open with a quote — deliberately, and correctly, for a string. A JSON boolean is not quoted,
     * so {@code {"wait": true}} — the form a model that obeys the schema writes — reads as absent
     * and {@code wait} silently does nothing. That is precisely the defect {@code Json.number} was
     * carved out of {@code read} to fix for integers, where one tool's {@code limit} was ignored on
     * every call any agent ever made. {@code Json.part} hands back the raw fragment, bare scalar
     * included, so both {@code true} and {@code "true"} mean wait here.
     */
    private static boolean flag(String json, String name) {
        return Args.flag(json, name) || "true".equalsIgnoreCase(Json.part(json, name).strip());
    }

    /** The closed set of four, always last, always on its own line. */
    private static String says(String status) {
        return "\n[status: " + status + "]";
    }

    /**
     * The id as the model wrote it, which is not always the id.
     *
     * <p>A model that writes {@code 3} or {@code #j3} where it meant {@code j3} has not made a
     * mistake worth a round trip: the answer is unambiguous and the alternative is a turn spent on
     * a formality in the middle of watching a build.
     */
    private Job find(String raw) {
        String want = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (want.startsWith("#")) {
            want = want.substring(1);
        }
        synchronized (registry) {
            Job named = registry.get(want);
            return named != null ? named : registry.get("j" + want);
        }
    }

    /** What a model that named the wrong job should try instead. */
    private String held() {
        List<String> ids;
        synchronized (registry) {
            ids = new ArrayList<>(registry.keySet());
        }
        return ids.isEmpty() ? "No background job has been started in this run."
                : "This run holds " + String.join(", ", ids) + "; job_list has the detail.";
    }

    private void keep(Job job) {
        synchronized (registry) {
            registry.put(job.id, job);
            // THE OLDEST ENDED JOB GOES AND A RUNNING ONE NEVER DOES. Forgetting a live job would
            // orphan a process nothing can then report on or kill, which is worse than holding a
            // thirty-third entry: the cap is on memory, and memory is not what a running build
            // costs.
            while (registry.size() > REMEMBERED) {
                String oldest = null;
                for (Map.Entry<String, Job> each : registry.entrySet()) {
                    if (each.getValue().ended()) {
                        oldest = each.getKey();
                        break;
                    }
                }
                if (oldest == null) {
                    return;
                }
                registry.remove(oldest);
            }
        }
    }

    /** One hook per registry, registered on the first job rather than in the constructor. */
    private void sweepOnExit() {
        synchronized (registry) {
            if (sweeping) {
                return;
            }
            sweeping = true;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::sweep, "ratchet-jobs-sweep"));
    }

    private void sweep() {
        List<Job> all;
        synchronized (registry) {
            all = new ArrayList<>(registry.values());
        }
        for (Job job : all) {
            if (job.process != null && job.process.isAlive()) {
                List<ProcessHandle> under = job.process.descendants().toList();
                job.process.destroy();
                under.forEach(ProcessHandle::destroy);
                job.process.destroyForcibly();
                under.forEach(ProcessHandle::destroyForcibly);
            }
        }
    }

    /**
     * WHAT IS ACTUALLY BEING RUN, FOR A LIST A MODEL SCANS.
     *
     * <p>The first word of the command is {@code bash} for every job {@code Shell} starts, and the
     * script behind {@code -lc} usually opens with the setup rather than the program: TOOLS.md
     * records the agent setting {@code JAVA_HOME} per invocation because one repository family
     * needs 21 and the other needs 11, and {@code cd} into the module before building is the same
     * shape. Assignments and those words are stepped over.
     *
     * <p>This label is never used for a decision — nothing here branches on it — so a wrong guess
     * costs a slightly less useful line in {@code job_list} and nothing else.
     */
    private static String kind(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "job";
        }
        String first = leaf(command.get(0));
        if (SHELLS.contains(first) && command.size() > 1) {
            String program = program(command.get(command.size() - 1));
            if (!program.isEmpty()) {
                return program;
            }
        }
        return shortened(first);
    }

    private static String program(String script) {
        for (String step : script.split("&&|\\|\\||;")) {
            for (String word : step.strip().split("\\s+")) {
                if (word.isEmpty() || word.indexOf('=') >= 0) {
                    continue;
                }
                if (BEFORE.contains(word)) {
                    break;
                }
                return shortened(leaf(word));
            }
        }
        return "";
    }

    private static String leaf(String path) {
        int at = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return at < 0 || at + 1 >= path.length() ? path : path.substring(at + 1);
    }

    /** Long enough for {@code gradlew} and a wrapper script's real name, short enough for a list. */
    private static String shortened(String word) {
        return word.length() <= 24 ? word : word.substring(0, 24);
    }

    /** An age a reader takes in at a glance: 47s, 3m 12s, 2h 05m. Never milliseconds past a second. */
    private static String spent(long millis) {
        if (millis < 1_000) {
            return millis + "ms";
        }
        long seconds = millis / 1_000;
        if (seconds < 90) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 90) {
            return minutes + "m " + (seconds % 60) + "s";
        }
        return (minutes / 60) + "h " + String.format(Locale.ROOT, "%02d", minutes % 60) + "m";
    }

    /**
     * ONE STARTED PROCESS AND THE OUTPUT NOBODY HAS READ YET.
     *
     * <p>Every field that both threads touch is behind this object's own monitor, because the drain
     * thread appends while the agent thread reads, and the two meet on a {@link StringBuilder} that
     * is neither thread-safe nor bounded by itself. The same monitor carries the blocking wait:
     * {@link #wrote} and {@link #finished} are the only two things a waiting read cares about, and
     * both notify.
     */
    private static final class Job {

        private final String id;
        private final String kind;
        private final String label;

        /** Null when the process never started, which is a job that is born ended. */
        private final Process process;

        private final long began;
        private final StringBuilder pending = new StringBuilder();
        private long produced;
        private long dropped;
        private boolean ended;
        private int exit;
        private long endedAt;
        private boolean killAsked;

        Job(String id, String kind, String label, Process process, long began) {
            this.id = id;
            this.kind = kind;
            this.label = label;
            this.process = process;
            this.began = began;
        }

        synchronized void wrote(String text) {
            produced += text.length();
            pending.append(text);
            int over = pending.length() - KEPT;
            if (over > 0) {
                // THE FRONT GOES, NOT THE BACK. The line that says why a build failed is the last
                // one it prints; the first is the JVM's version banner.
                pending.delete(0, over);
                dropped += over;
            }
            notifyAll();
        }

        synchronized void finished(int code, long at) {
            ended = true;
            exit = code;
            endedAt = at;
            notifyAll();
        }

        synchronized void asked() {
            killAsked = true;
        }

        synchronized boolean ended() {
            return ended;
        }

        synchronized long produced() {
            return produced;
        }

        synchronized int waiting() {
            return pending.length();
        }

        /**
         * KILLED IS SAID ONLY OF A JOB THAT HAS ACTUALLY STOPPED. A signalled process that is still
         * alive is still running, and saying otherwise would tell a model the lane is free when the
         * build is still holding it.
         */
        synchronized String status() {
            if (!ended) {
                return "running";
            }
            return killAsked ? "killed" : "exited " + exit;
        }

        /**
         * Up to {@code room} characters, consumed, cut at the last line break inside the window so
         * a chunk boundary does not fall through the middle of a compiler error.
         */
        synchronized String take(int room) {
            if (pending.length() == 0) {
                return "";
            }
            int upTo = Math.min(pending.length(), room);
            if (upTo < pending.length()) {
                int line = pending.lastIndexOf("\n", upTo);
                if (line > 0) {
                    upTo = line + 1;
                }
            }
            String chunk = pending.substring(0, upTo);
            pending.delete(0, upTo);
            return chunk;
        }

        /** How much was dropped unread since anyone last asked, and reset. Reported once. */
        synchronized long forgotten() {
            long was = dropped;
            dropped = 0;
            return was;
        }

        /** The tail of what is queued, consuming nothing, starting at a line break where it can. */
        synchronized String glimpse(int room) {
            if (pending.length() <= room) {
                return pending.toString();
            }
            int from = pending.length() - room;
            int line = pending.indexOf("\n", from);
            return pending.substring(line >= 0 && line + 1 < pending.length() ? line + 1 : from);
        }

        /** Blocks until there is something to read or the job ends, or the deadline passes. */
        synchronized void awaitAnything(Now now, long millis) {
            long deadline = now.millis() + millis;
            while (pending.length() == 0 && !ended) {
                if (!rest(now, deadline)) {
                    return;
                }
            }
        }

        /** Blocks until the job ends, or the deadline passes. */
        synchronized void awaitEnd(Now now, long millis) {
            long deadline = now.millis() + millis;
            while (!ended) {
                if (!rest(now, deadline)) {
                    return;
                }
            }
        }

        private boolean rest(Now now, long deadline) {
            long left = deadline - now.millis();
            if (left <= 0) {
                return false;
            }
            try {
                // Woken by wrote() or finished() the moment either happens; the tick is only so a
                // deadline measured on a handed-in clock is still noticed on a silent job.
                wait(Math.min(TICK, left));
                return true;
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        synchronized long ran(Now now) {
            return (ended ? endedAt : now.millis()) - began;
        }
    }
}