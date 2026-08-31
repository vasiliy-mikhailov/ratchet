package tech.mikhailov.ratchet.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Spilling;
import tech.mikhailov.ratchet.llm.Tool;

/**
 * THE TOOLS AN AGENT ACTUALLY REACHES FOR, ASSEMBLED. Opt-in, and this module is the opt.
 *
 * <p>ratchet shipped no tool implementations for four versions and the principle behind that is
 * still right: {@code Tool} is three strings and the LOOP must not decide what a caller's agent can
 * do. What the principle did not justify was leaving every consumer to write the same five hundred
 * lines. One carried an entire client library because four file tools came from it — and therefore
 * its loop, and therefore its transport. Replacing those four tools took 505 lines and removed
 * 1,053.
 *
 * <p>Taking this dependency is a choice; {@code Asking} still has no opinion about what it is
 * handed, and a consumer who wants three tools of their own passes three tools of their own.
 *
 * <p>THE SET IS MEASURED RATHER THAN CHOSEN, AND BY TWO CORPORA THAT DISAGREE. The first is
 * {@code TOOLS.md}: 816 calls over six runs, where {@code bash}, {@code write}, {@code edit},
 * {@code read} and {@code job_output} carry 97%, and {@code grep} is three calls in six runs. The
 * second is 11,328 calls over 24 lanes of a fake-writing pipeline, where {@code grep} is SECOND at
 * 15.9% and appears in 24 of 24 lanes. Both are real; they describe different work, and
 * {@link Search} explains which question produces which shape. Where they disagree the union wins,
 * because a tool nobody calls costs a few hundred bytes of schema and a tool somebody needs costs
 * them the task.
 *
 * <p>THE SCHEMAS ARE dsh'S, DELIBERATELY. {@code file_path}, {@code old_string},
 * {@code replace_all}, {@code pattern}, {@code include} — snake_case and named exactly as models
 * already meet them elsewhere. A tool that renames its arguments for house style asks every model
 * that arrives to guess.
 *
 * <p>THIS IS NOT A SANDBOX AND {@link Shell} SAYS SO AT LENGTH. A root directory bounds the file
 * tools and bounds nothing about {@code bash}, which runs what it is given as the user running the
 * JVM. dsh ships four sandbox packages and a policy layer around its own shell; this ships a
 * working directory. A caller who needs confinement builds it — or takes {@link #withoutShell},
 * which is the same set with no way to start a process in it.
 */
public final class Kit {

    private final Workspace workspace;
    private final Search search;
    private final Shell shell;
    private final Jobs jobs;
    private final Todos todos;

    private Kit(Path root, Duration timeout, int background, Spilling spilling, boolean shell) {
        Rooted rooted = new Rooted(root);
        this.workspace = new Workspace(rooted);
        this.search = new Search(rooted, Search.LONGEST);
        this.jobs = shell ? new Jobs(background) : null;
        this.shell = shell ? new Shell(root, timeout, jobs, spilling) : null;
        this.todos = new Todos();
    }

    /** Everything, rooted at {@code root}, with a five-minute default for one {@code bash} call. */
    public static Kit at(Path root) {
        return at(root, Duration.ofMinutes(5));
    }

    /**
     * The same, with the foreground shell timeout chosen.
     *
     * <p>Five minutes by default because a real build is the thing a tool call most often is, and a
     * bound shorter than one turns every honest build into a failure. Anything longer than a
     * consumer is willing to block for belongs in the background, which is what
     * {@code run_in_background} and {@link Jobs} are for.
     */
    public static Kit at(Path root, Duration foregroundTimeout) {
        return at(root, foregroundTimeout, Jobs.RUNNING);
    }

    /**
     * The same, with the ceiling on background processes chosen — and it is the one number here a
     * caller is most likely to need to change, because it is the only one about their MACHINE.
     *
     * <p>{@code run_in_background} returns immediately, so nothing about a tool call's own timeout
     * bounds how many builds a model may have running at once. {@link Jobs#RUNNING} says why the
     * shipped sixteen is a runaway guard rather than a shape; anyone sharing a box should say less.
     */
    public static Kit at(Path root, Duration foregroundTimeout, int backgroundJobs) {
        return at(root, foregroundTimeout, backgroundJobs, Spilling.none());
    }

    /**
     * The same, told where the rest of a result too big to send whole should go.
     *
     * <p>THIS IS THE ONE THAT KEEPS A RECORD HONEST. {@code bash} shows a model
     * {@link Retain#MOST} characters and holds up to a million; without this, everything between
     * the two is read, held, and dropped when the call returns — and a failing build's output is
     * the only copy of itself. {@code read} does not need it, because the file is still on disk and
     * the footer names the page. See {@link Shell#Shell(Path, Duration, Jobs, Spilling)}.
     */
    public static Kit at(Path root, Duration foregroundTimeout, int backgroundJobs,
            Spilling spilling) {
        return new Kit(root, foregroundTimeout, backgroundJobs, spilling, true);
    }

    /**
     * EVERYTHING EXCEPT {@code bash} AND THE JOB TOOLS — for a caller whose guarantees are enforced
     * at the tool boundary, which is the only place an unattended agent's guarantees can be.
     *
     * <p>THE CALLER THIS IS FOR SAID IT BEST. Their pipeline reverts an edit outside one directory
     * before the next turn, refuses the settings file and the git credential store by path, and
     * will not let the test configuration be weakened in either direction. None of that is a
     * request to the model: it holds because no tool they hand it can express the thing it forbids.
     * One {@code bash} call expresses all of them, and their runs are unattended, against
     * repositories cloned from somebody else's server, on a shared box. They were right to refuse,
     * and they were right that {@link #files()} on a kit that HAS a shell was the wrong answer —
     * a shell that exists and is filtered out is one refactor away from being handed over.
     *
     * <p>So this kit does not construct one. There is no {@link Shell} and no {@link Jobs} behind
     * it, and no arrangement of the object yields either.
     *
     * <p>WHAT IT DOES NOT MEAN. It is not a sandbox and does not become one by subtraction. The JVM
     * this runs in can still do everything a JVM can do, the file tools still follow a symlink out
     * of the root ({@link Rooted} says so), and a caller who hands the model some OTHER tool that
     * runs a process has given back exactly what this withheld. What it means is narrow and worth
     * having: nothing in this set starts a process.
     */
    public static Kit withoutShell(Path root) {
        return new Kit(root, Duration.ZERO, Jobs.RUNNING, Spilling.none(), false);
    }

    /** All of them, in the order a reader of this class would expect to meet them. */
    public Map<Tool, Calling> tools() {
        Map<Tool, Calling> all = new LinkedHashMap<>();
        all.putAll(files());
        if (shell != null) {
            all.putAll(shell.tools());
            all.putAll(jobs.tools());
        }
        all.putAll(todos.tools());
        return all;
    }

    /**
     * THE FILE TOOLS AND THE SEARCH TOOLS: {@code read}, {@code write}, {@code edit},
     * {@code list_dir}, {@code grep}, {@code glob}. For a caller who has a shell of their own.
     *
     * <p>{@code grep} and {@code glob} are here rather than beside {@code bash} for the reason
     * {@link Search} gives: leaving them out because "bash can do it" is exactly wrong for the
     * caller who has decided not to take {@code bash}, and that caller is the one this method and
     * {@link #withoutShell} exist for.
     */
    public Map<Tool, Calling> files() {
        Map<Tool, Calling> all = new LinkedHashMap<>();
        all.putAll(workspace.tools());
        all.putAll(search.tools());
        return all;
    }

    /** The task list, for a caller that wants to show it rather than only let a model write it. */
    public Todos todos() {
        return todos;
    }

    /** Background work, for a caller that wants to know what is still running. Null without a shell. */
    public Jobs jobs() {
        return jobs;
    }
}
