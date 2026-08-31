package tech.mikhailov.ratchet.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import tech.mikhailov.ratchet.llm.Calling;
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
 * <p>THE SET IS MEASURED RATHER THAN CHOSEN. See {@code TOOLS.md}: 816 calls across six runs of one
 * real task, where {@code bash}, {@code write}, {@code edit}, {@code read} and {@code job_output}
 * carry 97% between them. {@code todo_write} is 1.3% and appears in six runs of six, which is the
 * argument for it. {@code grep} was called three times in six runs and is absent here for the reason
 * that measurement gives: {@code bash} can do it.
 *
 * <p>THE SCHEMAS ARE dsh'S, DELIBERATELY. {@code file_path}, {@code old_string}, {@code replace_all}
 * — snake_case and named exactly as models already meet them elsewhere. A tool that renames its
 * arguments for house style asks every model that arrives to guess.
 *
 * <p>THIS IS NOT A SANDBOX AND {@link Shell} SAYS SO AT LENGTH. A root directory bounds the file
 * tools and bounds nothing about {@code bash}, which runs what it is given as the user running the
 * JVM. dsh ships four sandbox packages and a policy layer around its own shell; this ships a
 * working directory. A caller who needs confinement builds it or does not take this module.
 */
public final class Kit {

    private final Workspace workspace;
    private final Shell shell;
    private final Jobs jobs;
    private final Todos todos;

    private Kit(Path root, Duration timeout) {
        this.jobs = new Jobs();
        this.workspace = new Workspace(root);
        this.shell = new Shell(root, timeout, jobs);
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
        return new Kit(root, foregroundTimeout);
    }

    /** All of them, in the order a reader of this class would expect to meet them. */
    public Map<Tool, Calling> tools() {
        Map<Tool, Calling> all = new LinkedHashMap<>();
        all.putAll(workspace.tools());
        all.putAll(shell.tools());
        all.putAll(jobs.tools());
        all.putAll(todos.tools());
        return all;
    }

    /** Just the four file tools, for a caller who has a shell of their own. */
    public Map<Tool, Calling> files() {
        return workspace.tools();
    }

    /** The task list, for a caller that wants to show it rather than only let a model write it. */
    public Todos todos() {
        return todos;
    }

    /** Background work, for a caller that wants to know what is still running. */
    public Jobs jobs() {
        return jobs;
    }
}
