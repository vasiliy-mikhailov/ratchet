package tech.mikhailov.ratchet.flow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import tech.mikhailov.ratchet.record.Journal;
import tech.mikhailov.ratchet.record.Trace;

/**
 * THE SHAPE IS THE PROGRAM, RATHER THAN A DESCRIPTION OF IT.
 *
 * <p>A file called {@code Chain} existed because the structure of a run lived in two places:
 * declared there, and carried out in the class that ran it. Two copies of one fact drift, and that pair did:
 * a page drew a loop between two stages that had never joined, a label said a phase ran once for
 * the whole subject days after it became per part, and four prompts opened by telling agents a
 * gate had failed that no longer ran before them. Every one of those was found by reading, not by
 * anything failing. It is gone; {@link Shape} walks this tree for everything the pages read off it.
 *
 * <p>The fix is not another test binding the two. It is to stop having two. An agent is
 * {@code String run(String)}; a triad is three of those with a loop; a sequence is several of them
 * in order; a walk is one of them per item; plain code is one of them that calls no model. All the
 * same interface, so they compose, and the composition IS the shape. Nothing has to be kept in step
 * with it because there is nothing else.
 *
 * <p>THAT SENTENCE IS THE FILE NOW, rather than a claim about it. A triad was named in it as one of
 * the five and shipped as a class next door, so the prose said combinator while the layout said the
 * opposite: one fact in two places again, in the paragraph complaining about one fact in two
 * places. {@link #triad} is a factory beside the rest and returns a {@link Node} like they do.
 *
 * <p>THIS IS SEQUENCE, SELECTION AND ITERATION, and that is the whole point. Structured programming
 * replaced the jump with three combinators over one notion of statement. These are three
 * combinators over one notion of agent, and they buy the same thing: a text whose indentation is
 * its control flow, and a worst case you compute by reading rather than by simulating.
 *
 * <p>Every node carries {@link Agent#inside()}, so the picture is derived by walking the
 * thing that runs. A diagram drawn beside a program can be wrong and stay wrong; a diagram that IS
 * the program cannot.
 */
public final class Flow {

    private Flow() {
    }

    /** A step of plain code, which is an agent like any other and calls no model. */
    @FunctionalInterface
    public interface Step {
        String run(String task) throws IOException;
    }

    /** One execution of one plan. Not necessarily an agent: a doer may be a build, a script, or
     *  any step that is not asked, only run. */
    @FunctionalInterface
    public interface Doer {
        /**
         * @param plan     what the planner settled on, unchanged across {@code again} rounds
         * @param feedback the verifier's objection, empty on the first round
         */
        String run(String plan, String feedback) throws IOException;
    }

    /**
     * What the workspace says once the doer has run.
     *
     * <p>Every verifier is handed this rather than left to ask. One corpus has a preparer answering
     * NOTHING-TO-DO while its own stage recorded edits, and a troubleshooter reporting a fix it had
     * reverted a turn earlier; a report is an opinion and the workspace is not.
     */
    @FunctionalInterface
    public interface Facts {
        String read() throws IOException;
    }

    /**
     * THE RUN IS OVER BEFORE THE SEQUENCE IS, and saying so is a jump.
     *
     * <p>It is named and it lives here, next to the paragraph about having replaced the jump,
     * rather than hidden in the caller. Three things end a run before anything can be judged, and
     * none of them is a stage deciding something: the tooling it needs is not there, the work will
     * not start from a state anyone can measure, and there is no measurement to conserve. None of
     * them is selection: guarding every later stage with "and we are still going" would put error
     * handling into the picture and stop it being the shape of the program. Nobody chose to skip
     * the gate. The run stopped.
     *
     * <p>It carries the settlement account, whose first line is the state the sweep files. The
     * safety of that is a property of what sits between a throw and its catch, so it is worth
     * saying what does: in the run this came from the only throwers are plain-code nodes at the
     * top, and neither {@link #code} nor {@link #seq} catches anything on the way out. Two broad
     * catches do exist around the model call inside an agent, and no settlement passes through
     * them. One added on this path would swallow the account, and the runner would then read a
     * settled run as a crash.
     */
    public static final class Settled extends RuntimeException {

        private final String account;

        public Settled(String account) {
            super(account);
            this.account = account;
        }

        /** The settlement, in the form the caller returns it: state on the first line, then why. */
        public String account() {
            return account;
        }
    }

    /**
     * A NAMED NODE. Everything here is one, because a node without a name cannot be traced, cannot
     * be drawn, and cannot be pointed at in a bug report.
     *
     * <p>IT ALSO CARRIES THE THREE THINGS A WALK CANNOT DERIVE. Who speaks inside a node, how often
     * the node runs, and which list it works to: the agents are called
     * from inside a body, and a condition is a {@link BooleanSupplier} with no English in it. They
     * were declared in a file of their own beside the program, and all three drifted. Here they sit
     * on the node, one line above the body that makes them true, and they die with it.
     *
     * <p>The vocabulary is the one that file used, because the facts are the same facts: a stage is
     * a {@link #triplet}, or a plan and a verdict {@link #around} a step no model makes, or
     * {@link #deterministic}, which is a fact with nothing to plan and nothing to dispute.
     */
    public abstract static class Node implements Agent {
        private final String label;
        private List<Shape.Step> steps = List.of();
        private String repeats = "";
        private String reads = "";

        Node(String label) {
            this.label = label;
        }

        @Override
        public String name() {
            return label;
        }

        /** Who speaks inside this node, and which of them is a step no model makes. */
        List<Shape.Step> steps() {
            return steps;
        }

        /** How often it runs, which nesting cannot say and a reader will otherwise guess wrongly. */
        String repeats() {
            return repeats;
        }

        /** Which list it works to, empty for a node that reads none. */
        String reads() {
            return reads;
        }

        /** Plans, does and verifies: the three agents named after this node. */
        public Node triplet() {
            steps = List.of(Shape.agent(label, "planner"), Shape.agent(label, "doer"),
                    Shape.agent(label, "verifier"));
            return this;
        }

        /** A plan and a verdict around a step no model makes: a sub-chain, a build, a read. */
        public Node around(String step) {
            steps = List.of(Shape.agent(label, "planner"), Shape.step(step),
                    Shape.agent(label, "verifier"));
            return this;
        }

        /** Only the step: a fact, with nothing to plan and nothing to dispute. */
        public Node deterministic() {
            steps = List.of(Shape.step(label));
            return this;
        }

        public Node repeats(String often) {
            repeats = often;
            return this;
        }

        public Node reads(String part) {
            reads = part;
            return this;
        }
    }

    /** Plain code as an agent. The gate, a build, a scan: things with no model in them. */
    public static Node code(String name, Step body) {
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                return body.run(task);
            }
        };
    }

    /**
     * PLAIN CODE WITH A NODE INSIDE IT, which its own body reaches when and as often as it decides.
     *
     * <p>The three combinators cover the shapes a caller can hand over: in order, once per item,
     * until a condition clears. A campaign is none of those from the outside. Its steps are a loop
     * inside a loop, with a planner above them and a critic between the rounds, and rewriting that
     * as nested {@code loop}s to make it drawable would rewrite live repair to suit a picture.
     *
     * <p>So the node is real and the body runs it. The picture shows the stage under its parent
     * because the parent holds it, and every step the run orders goes through it because the code
     * has no other way to reach the campaign. What this does NOT claim is a count: how many times
     * the body runs the node inside it is the body's business, which is what {@link #repeats} is
     * for.
     */
    public static Node code(String name, Agent inner, Step body) {
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                return body.run(task);
            }

            @Override
            public List<Agent> inside() {
                return List.of(inner);
            }
        };
    }

    /**
     * One after another, each handed the same brief.
     *
     * <p>THE LAST WORD IS THE SEQUENCE'S WORD, which is the same rule a triad's doer follows. A
     * sequence that concatenated every step's answer would hand the next reader a transcript rather
     * than a result, and every caller would then have to decide which part of it mattered.
     */
    public static Node seq(String name, Agent... steps) {
        List<Agent> all = List.of(steps);
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                String last = "";
                for (Agent step : all) {
                    last = step.run(task);
                }
                return last;
            }

            @Override
            public List<Agent> inside() {
                return all;
            }
        };
    }

    /**
     * The same work once per item, with the item's own name in the brief.
     *
     * <p>The list is a supplier rather than a list, because what a walk walks is decided by a stage
     * that ran earlier: the filter chooses the items and it is itself in the sequence.
     *
     * <p>{@code inside()} reports the body built for a null item, so the picture can be drawn before
     * anything has run. That is a real limitation and an honest one: a shape is what the program can
     * do, not what one execution did.
     */
    public static <T> Node each(String name, Supplier<List<T>> items,
                         Function<T, String> label, Function<T, Agent> body) {
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                String last = "";
                for (T item : items.get()) {
                    last = body.apply(item).run(task + "\n\nThis pass is for: " + label.apply(item));
                }
                return last;
            }

            @Override
            public List<Agent> inside() {
                return List.of(body.apply(null));
            }
        };
    }

    /**
     * Until it says it is done, or the turns run out.
     *
     * <p>The condition is asked BEFORE each turn and again after the body, so a block whose work is
     * already unnecessary costs nothing. A loop that always ran once before checking is how a
     * repository that needed no repair still paid for a repair planner.
     *
     * <p>A TURN IS ITS STEPS, IN ORDER, rather than a single step. A gate is a check and then,
     * only when that check came back red, a repair. Writing the pair as a nameless sequence
     * inside the loop would put a node in the picture that nobody could point at, in order to say
     * something the loop already says.
     */
    public static Node loop(String name, int turns, BooleanSupplier again, Agent... turn) {
        int bound = Math.max(1, turns);
        List<Agent> steps = List.of(turn);
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                String last = "";
                for (int round = 1; round <= bound && again.getAsBoolean(); round++) {
                    String brief = task + "\n\nTurn " + round + " of at most " + bound + ".";
                    for (Agent step : steps) {
                        last = step.run(brief);
                    }
                }
                return last;
            }

            @Override
            public List<Agent> inside() {
                return steps;
            }
        };
    }

    /** Only when the condition holds. Selection, and the reason a stage can say "only after a green gate". */
    public static Node when(String name, BooleanSupplier cond, Agent body) {
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                return cond.getAsBoolean() ? body.run(task) : "";
            }

            @Override
            public List<Agent> inside() {
                return List.of(body);
            }
        };
    }

    /**
     * PLANNER, DOER, VERIFIER, AND THE VERIFIER HOLDS THE LOOP.
     *
     * <p>Every stage was a producer and a critic, with the loop written into the producer's side:
     * the producer ran repeatedly and the critic was asked once, at the end. That put the two jobs
     * in the wrong hands. Deciding what to do and deciding whether it worked are different
     * questions, and the agent that chose a plan is the worst available judge of whether to keep
     * running it.
     *
     * <p>It also hid a real defect for as long as it existed. One stage looped its producer and
     * called a check between rounds, but the check answered about the wrong one of many subjects:
     * it read every candidate at once and returned the first value it matched anywhere, so it
     * reported on whichever the filesystem happened to walk first. Sixteen of the twenty-seven
     * many-part subjects in that corpus carry the same thing at more than one value. The loop
     * terminated reporting every check met, the critic saw only that end state and agreed, and
     * nothing in the construction could have caught it. A verifier that runs every round, against
     * one subject at a time, cannot make that mistake.
     *
     * <p>So the doer executes one plan once and has no opinion about repetition. The verifier reads
     * what the workspace says afterwards and returns one of three words, which is the whole control
     * flow:
     *
     * <ul>
     *   <li>{@code done} closes the stage.
     *   <li>{@code again} keeps the plan and re-runs the doer, which is told what the objection
     *       was.
     *   <li>{@code replan} throws the plan away and returns to the planner.
     * </ul>
     *
     * <p>The two failure paths are separate on purpose. Collapsing them is how a loop spends its
     * whole budget re-running a plan that was wrong from the first round, which is the shape of the
     * sixteen gate turns one corpus spent watching a troubleshooter re-apply an edit its reviewer
     * had already rejected.
     *
     * <p>Nesting is the same object: a doer may itself be a triad, and each level loops on its own
     * verifier. The inner one closes first, so an outer verifier judges a finished piece of work
     * rather than a half-run loop.
     *
     * <p>WHY THIS IS A FACTORY HERE AND NOT A CLASS OF ITS OWN. The paragraph at the top of this
     * file names a triad as one of the five productions of the grammar, and for a while it was the
     * one production that lived somewhere else: the prose said combinator, the file layout said
     * otherwise, and that is one fact in two places, which is the defect the same paragraph is
     * about. It returns a {@link Node} and composes exactly like the other six. Arity and meaning
     * being fixed does not make it something else; the ternary operator is opinionated about both
     * and is still an operator.
     */
    public static Node triad(String stage, Agent planner, Doer doer, Agent verifier, Facts facts,
                             Trace trace, String key, int rounds) {
        return new Triad(stage, planner, doer, verifier, facts, trace, key, rounds);
    }

    /**
     * A NAMED CLASS RATHER THAN AN ANONYMOUS ONE, unlike the six factories above it. Those are a
     * body and nothing else. This one holds seven collaborators, a loop with two exits and three
     * helpers that are worth reading on their own, and hanging that off a {@code new Node(name) {}}
     * would bury the factory it belongs to a hundred lines above its own closing brace. It is
     * private, so what a caller can name is still only {@link #triad} and the {@link Node} it hands
     * back.
     */
    private static final class Triad extends Node {

        private final Agent planner;
        private final Doer doer;
        private final Agent verifier;
        private final Facts facts;
        private final Trace trace;
        private final String key;
        private final int rounds;

        Triad(String stage, Agent planner, Doer doer, Agent verifier, Facts facts,
                Trace trace, String key, int rounds) {
            super(stage);
            this.planner = planner;
            this.doer = doer;
            this.verifier = verifier;
            this.facts = facts;
            this.trace = trace;
            this.key = key;
            this.rounds = Math.max(1, rounds);
        }

        /**
         * WHAT IT CONTAINS, so a triad can be drawn without anyone writing the drawing down twice.
         *
         * <p>The planner and the verifier are leaves here rather than named children: naming them
         * would put three lines on a picture where the interesting fact is one, that this stage
         * plans, does and verifies like every other. What is worth showing is what the DOER
         * contains, which is where a sub-chain lives.
         */
        @Override
        public List<Agent> inside() {
            return doer instanceof Agent nested ? List.of(nested) : List.of();
        }

        /** What the stage ended up having done, which is the doer's last word. */
        @Override
        public String run(String brief) throws IOException {
            String plan = planner.run(brief);
            String feedback = "";
            String did = "";
            for (int round = 1; round <= rounds; round++) {
                did = doer.run(plan, feedback);
                String state = facts.read();
                String judgement = verifier.run(brief
                        + "\n\nThe plan this stage is working to:\n" + plan
                        + "\n\nWhat your colleague reports doing:\n" + did
                        + "\n\nWhat the workspace says now:\n" + state);
                String verdict = verdictOf(judgement);
                if (verdict.equals("done")) {
                    trace.progress(key, name() + ": settled after " + round
                            + (round == 1 ? " round" : " rounds"));
                    return did;
                }
                if (round == rounds) {
                    // The budget is spent, and saying so beats a verdict nobody reached.
                    trace.progress(key, name() + ": " + rounds + " rounds spent, last word was "
                            + verdict + " — " + because(judgement));
                    return did;
                }
                trace.progress(key, name() + ": " + verdict + " — " + because(judgement));
                if (verdict.equals("replan")) {
                    plan = planner.run(brief
                            + "\n\nYour previous plan:\n" + plan
                            + "\n\nIt was carried out, and a reviewer sent the whole plan back:\n"
                            + judgement
                            + "\n\nWhat the workspace says now:\n" + state
                            + "\n\nPlan again. The objection is to the plan, not to the execution,"
                            + " so a plan that differs only in wording will come straight back.");
                    feedback = "";
                } else {
                    feedback = "\n\nYou did this once already and a reviewer objected:\n"
                            + judgement
                            + "\n\nWhat the workspace says now:\n" + state
                            + "\n\nThe plan stands. Address the objection.";
                }
            }
            return did;
        }

        /**
         * The verifier's word, with a blank reply read as {@code again} rather than as agreement.
         *
         * <p>{@link Reply#word} falls back to its first argument, and an empty reply is a live
         * failure mode on a small local model rather than a hypothetical. Defaulting silence to
         * {@code done} would close a stage because a request came back empty, which is the one
         * reading of silence that loses work.
         */
        private static String verdictOf(String judgement) {
            // WHAT SILENCE MEANS IS DECIDED HERE, ONCE. Reply.word used to fall back to its first
            // argument — which this call passes as "done" — so a verifier that said nothing
            // approved, and the blank check above existed to undo it. Two layers doing one job,
            // with the lower one defaulting to the reading that loses work. Reply.word answers ""
            // for a reply that names no verdict now, and a reply that names no verdict is again.
            String said = Reply.word(judgement, "done", "again", "replan");
            return said.isEmpty() ? "again" : said;
        }

        /**
         * THE FIRST LINE THAT SAYS SOMETHING, which is not the same as the first line.
         *
         * <p>This took {@code lines().findFirst()} literally, and a reply that opens with a newline
         * therefore logged as nothing at all. Measured over 1,544 verifier replies in one corpus:
         * 1,468 of them, 95 per cent, began with a blank line, so the progress note carried the
         * stage and the verdict and then stopped, with the objection missing. The objection itself
         * was never lost, because the whole judgement is spliced into the doer's feedback a few
         * lines above; what was lost was the one line a person reads to work out why a run is still
         * going.
         */
        private static String firstLine(String s) {
            return s == null ? "" : s.lines().map(String::strip).filter(l -> !l.isEmpty())
                    .findFirst().orElse("");
        }

        /**
         * WHAT TO WRITE WHEN THERE IS NOTHING TO WRITE, and why it is not the same note.
         *
         * <p>{@link #verdictOf} reads silence as {@code again}, deliberately: defaulting it to
         * {@code done} would close a stage because a request came back empty. But then the note for
         * a reviewer who objected and the note for a reviewer who said nothing are the same
         * sentence, and they call for opposite responses. It happened 64 times in 1,544 calls,
         * about one in twenty-four, so it is worth telling apart.
         */
        private static String because(String judgement) {
            return judgement == null || judgement.isBlank()
                    ? "the verifier answered nothing, which is read as again rather than as agreement"
                    : firstLine(judgement);
        }
    }

    /**
     * THE SAME NODE, MINUS WHAT IT HAS ALREADY DONE.
     *
     * <p>A run is killed and restarted constantly, because a sweep goes on for a fortnight and the
     * harness changes daily. Almost everything the run knows is cheap to derive again: the agents
     * are stateless, so there is no conversation to rebuild, and the workspace is durable, so what
     * was edited is on disk where the readers re-read it for free. What is not cheap is the model
     * calls. This returns the answer the {@link Journal} already holds for (node, key), and
     * otherwise runs the node and journals what it returned.
     *
     * <p>A DECORATOR, so that nothing else here changes. Resumability is not a fourth combinator:
     * it does not decide anything, it does not repeat anything, and a run built out of it reads
     * exactly like the run without it.
     *
     * <p>THE KEY IS SUPPLIED RATHER THAN FIXED BECAUSE A WALK REPEATS THE NODE. The same node
     * completes once for every item, and a key decided when the tree was built would be the same
     * key for all of them.
     *
     * <p>{@link Agent#name()} AND {@link Agent#inside()} DELEGATE, and that is not decoration. The
     * pages walk the tree for the shape, and {@link Shape} reads {@code steps}, {@code repeats} and
     * {@code reads} off {@link Node}: a wrapper that reported its own would delete every wrapped
     * stage from the picture and orphan everything beneath it. The name is also the journal's own
     * node column, so an unnamed node cannot be journaled without colliding with every other
     * unnamed one, and this refuses rather than resuming the wrong thing.
     *
     * <p>The row is written after the node returns, which is the ordering the journal's safety rests
     * on: see {@link Journal#done}. A node that ends the run instead of returning, by throwing
     * {@link Settled}, journals nothing, and the resume runs it again.
     */
    public static Node resumable(Agent node, Journal journal, Supplier<String> key) {
        if (node.name().isBlank()) {
            throw new IllegalArgumentException("a node with no name cannot be journaled");
        }
        return new Node(node.name()) {
            @Override
            public String run(String task) throws IOException {
                String forKey = key.get();
                Optional<String> already = journal.answered(node.name(), forKey);
                if (already.isPresent()) {
                    return already.get();
                }
                String answer = node.run(task);
                journal.done(node.name(), forKey, answer);
                return answer;
            }

            @Override
            public List<Agent> inside() {
                return node.inside();
            }

            @Override
            List<Shape.Step> steps() {
                return node instanceof Node inner ? inner.steps() : super.steps();
            }

            @Override
            String repeats() {
                return node instanceof Node inner ? inner.repeats() : super.repeats();
            }

            @Override
            String reads() {
                return node instanceof Node inner ? inner.reads() : super.reads();
            }
        };
    }

    /**
     * A BLOCK OF THE TREE, STANDING AS A TRIAD'S DOER.
     *
     * <p>{@link #triad} reports its doer when the doer is an agent, and that is the hook
     * that lets a stage's picture show what happens underneath it. A doer written as a lambda is
     * not an agent, so a stage whose work is a whole walk drew as a leaf: the stage was in the
     * picture and everything it did was not.
     *
     * <p>It is abstract on purpose. The two things a doer is handed, the plan and the verifier's
     * objection, are the block's to place, because only the block knows which of its stages can act
     * on them. A default that spliced them into the task would put the walk's whole transcript in
     * front of an agent that was asked about one item.
     */
    public abstract static class Block implements Doer, Agent {

        /** The nested tree: what this doer runs, and what the picture shows under its stage. */
        public final Agent body;

        public Block(Agent body) {
            this.body = body;
        }

        @Override
        public String run(String task) throws IOException {
            return body.run(task);
        }

        @Override
        public List<Agent> inside() {
            return List.of(body);
        }
    }

    /**
     * THE SHAPE, AS TEXT, WALKED OFF THE THING THAT RUNS.
     *
     * <p>This is the whole argument in one method. The picture is not drawn beside the program and
     * kept in step by hand; it is the program, printed. It cannot point at two stages that never
     * joined, because it has no coordinates to get wrong.
     */
    public static String shape(Agent root) {
        StringBuilder out = new StringBuilder();
        draw(root, 0, out);
        return out.toString();
    }

    private static void draw(Agent node, int depth, StringBuilder out) {
        if (!node.name().isEmpty()) {
            out.append("    ".repeat(depth)).append(node.name()).append('\n');
        }
        int next = node.name().isEmpty() ? depth : depth + 1;
        for (Agent child : node.inside()) {
            draw(child, next, out);
        }
    }

    /** Every named node, in the order the program reaches them. What a page or a test walks. */
    public static List<String> names(Agent root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Agent node, List<String> out) {
        if (!node.name().isEmpty()) {
            out.add(node.name());
        }
        for (Agent child : node.inside()) {
            collect(child, out);
        }
    }
}
