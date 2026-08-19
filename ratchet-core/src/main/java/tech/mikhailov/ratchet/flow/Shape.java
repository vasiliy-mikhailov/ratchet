package tech.mikhailov.ratchet.flow;

import java.util.ArrayList;
import java.util.List;

/**
 * THE PROGRAM AS A PAGE READS IT, WALKED OFF THE THING THAT RUNS.
 *
 * <p>A file called {@code Chain} used to answer this. It declared the stages, their order, their
 * nesting, how often each one ran and which list each one worked to, beside the class that did all
 * five for real. Two copies of one fact drift, and every part of that pair drifted at least once:
 * the page advertised a {@code prepare} stage for hours after all three of its agents were
 * deleted, a label said a phase ran once for the whole subject days after it became per part, and
 * a strip drew a loop between two stages that had never joined. Nothing failed and no test went
 * red. A reader was simply told something untrue by the page whose whole job is to say what a run
 * is doing.
 *
 * <p>So there is no declaration left. {@link Flow} builds the run as one tree of named nodes and
 * this walks that tree, which is the same object the runtime executes. A stage cannot be advertised
 * after it is deleted, because deleting it deletes the node the page was reading.
 *
 * <p>THREE THINGS A WALK CANNOT DERIVE, and they are declared on the nodes rather than here: who
 * speaks inside a node, how often the node runs, and which list it works to. An agent is called from
 * inside a body, and a condition is a {@code BooleanSupplier} with no
 * English in it. See {@link Flow.Node}: each of the three is written one line above the body that
 * makes it true, and dies with that body.
 */
public final class Shape {

    private Shape() {
    }

    /**
     * One step of one stage: an agent this harness prompts, or a deterministic step it runs itself.
     *
     * <p>The role is part of the name, because the name is what a reader sees. Where a doer is a
     * deterministic step rather than an agent it is marked as one: a walk and a repair campaign
     * both "do" by running a sub-chain, and pretending a model sits there would be its own
     * untruth.
     */
    public record Step(String name, String role, boolean agent) {
    }

    /**
     * One stage: where it sits, who speaks in it, how often it runs, and which list it reads.
     *
     * <p>{@code within} names the stage this one hangs under, empty at the top. See {@link #of} for
     * why that is the nearest ancestor that SPEAKS rather than the nearest ancestor there is.
     */
    public record Stage(String title, String within, List<Step> steps, String repeats, String reads) {

        public boolean nested() {
            return !within.isBlank();
        }

        /** Whether any model is prompted here. A stage that speaks to nobody is a fact, not a step. */
        public boolean speaks() {
            return steps.stream().anyMatch(Step::agent);
        }
    }

    /** An agent of a stage, whose name is the stage's and whose role is the rest of it. */
    static Step agent(String stage, String role) {
        return new Step(stage + "-" + role, role, true);
    }

    /** A step no model makes: a sub-chain, a build, a read. */
    static Step step(String name) {
        return new Step(name, "doer", false);
    }

    /**
     * EVERY STAGE OF A TREE, IN THE ORDER THE PROGRAM REACHES THEM.
     *
     * <p>A node is a stage when something speaks or something is done in it that a page can name.
     * Nodes that name no step at all are scope rather than stage: a walk's own body says nothing
     * that the {@code repeats} on the stage above it has not already said.
     *
     * <p>WHERE THE PAGE HANGS A STAGE IS NOT ALWAYS WHERE THE TREE DOES, and the difference is
     * deliberate. A page nests by what a reader can click on, and it recurses by title through the
     * stages that have agents; a stage hung under one that prompts nobody is a heading with nothing
     * under it and everything below it orphaned. A gate that names a step and prompts nobody makes
     * the repair beneath it report the nearest ancestor that speaks instead. The tree keeps the
     * more specific truth and {@link Flow#shape} prints it: repair runs inside the gate's own turn
     * rather than beside it.
     */
    public static List<Stage> of(Agent root) {
        List<Stage> out = new ArrayList<>();
        walk(root, "", out);
        return out;
    }

    private static void walk(Agent node, String within, List<Stage> out) {
        String under = within;
        if (node instanceof Flow.Node n && !n.name().isEmpty() && !n.steps().isEmpty()) {
            Stage stage = new Stage(n.name(), within, n.steps(), n.repeats(), n.reads());
            out.add(stage);
            if (stage.speaks()) {
                under = stage.title();
            }
        }
        for (Agent child : node.inside()) {
            walk(child, under, out);
        }
    }

    /** Every agent the tree reaches, in the order it reaches them. What a catalogue must define. */
    public static List<String> agentNames(List<Stage> stages) {
        return stages.stream()
                .flatMap(s -> s.steps().stream())
                .filter(Step::agent)
                .map(Step::name)
                .toList();
    }
}
