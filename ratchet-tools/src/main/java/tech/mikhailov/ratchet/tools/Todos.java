package tech.mikhailov.ratchet.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tech.mikhailov.ratchet.llm.Called;
import tech.mikhailov.ratchet.llm.Calling;
import tech.mikhailov.ratchet.llm.Tool;
import tech.mikhailov.ratchet.record.Json;

/**
 * THE TASK LIST, AND IT IS WRITTEN WHOLE EVERY TIME.
 *
 * <p>ONE TOOL RATHER THAN FOUR, WHICH IS dsh'S CONTRACT AND NOT AN ABBREVIATION OF IT. The model
 * sends the ENTIRE list on every call and the last write wins. There is no add, no complete, no
 * reorder, and no per-item id — so there is nothing to address an item BY, and no way for the list
 * the model is reasoning about to drift from the list that is held. An item left out of a call is
 * deleted by being left out. That is the price of the contract and it is also the whole of the
 * mechanism: the state is exactly the last thing the model said, and it never has to be merged.
 *
 * <p>IT IS 1.3% OF CALLS AND THAT IS NOT THE NUMBER IT SHIPS ON. Across 816 tool calls in six runs
 * of one task (TOOLS.md at the root) {@code todo_write} was called 11 times — but it was reached for
 * in 6 of 6 runs, which no tool but {@code bash} also managed. {@code write} appeared in five runs,
 * {@code edit} in four, {@code grep} in two. Presence is the measurement that matters for something
 * holding one field of state and parsing two strings an item: every trajectory wanted it, and it is
 * cheap at any share.
 *
 * <p>ONE INSTANCE PER AGENT. The list is that agent's plan, and two agents handed the same instance
 * overwrite each other on every call rather than sharing anything — whole-list writes make that
 * silent. {@link #tools()} may be called as often as the caller likes; the state is on the object.
 *
 * <p>THE HELD LIST IS REPLACED AND NEVER EDITED. {@code Asking} runs tools one at a time on its own
 * thread, but {@link #current()} exists for a caller rendering the list somewhere else — a status
 * line, a log, a page — and that is a different thread. The reference is volatile and what it points
 * at is immutable, so a reader sees the list from before a write or the list from after it, and
 * never one halfway through being built.
 *
 * <p>THIS IS THE ONE PLACE IN THE PACKAGE THAT READS A NESTED DOCUMENT, AND IT IS TWENTY-THREE LINES
 * OF SCANNER. {@code Json.read} scans flat and takes the FIRST match, so run over the whole
 * arguments string it returns the first todo's content once for every item in the list.
 * {@code Json.part} descends exactly one step, which reaches the array but has no step from an array
 * to its elements. That gap is all {@link #objects} fills: it counts braces, respecting strings and
 * their escapes, and hands each element back as a substring for {@code Json.read} to read flat —
 * which is what {@code read} is right for, because one todo IS flat.
 *
 * <p>A REAL JSON PARSER IS STILL NOT WORTH A DEPENDENCY, AND THIS FILE IS THE ARGUMENT AGAINST ONE
 * RATHER THAN FOR IT. The document is not arbitrary JSON; it is the shape this class itself
 * advertises — an array of objects of two strings, nothing nested under them and nothing
 * recursive — so the hard parts of a parser have no work to do here, and twenty-three lines cover
 * what is left. The alternative is a jar inherited by every consumer of ratchet-tools, in a process
 * that may already be running arbitrary code out of a stranger's repository, which is the reason
 * ratchet-core hand-wrote {@code Json} in the first place. One field turning out to be an array did
 * not repeal that reason.
 *
 * <p>NOTHING HERE REFUSES A WRITE. A status outside the three is recorded as {@code pending} and
 * named in the answer; an item with no content is dropped and counted; arguments cut off mid-list
 * keep the items that were complete. Rejection is expensive under a whole-list contract, because
 * the model's entire plan is inside the call being rejected — so this takes what it can read and
 * says precisely what it took.
 *
 * <p>THE ONE THING THAT EMPTIES THE LIST IS AN EMPTY ARRAY, which is the exception the paragraph
 * above needs. Under last-write-wins the two cases arrive in the same shape: a call saying
 * {@code "todos":[]} is an instruction and is obeyed, while a call with nothing legible in it —
 * malformed, truncated to nothing, an array of something that is not a todo — is not an instruction
 * and leaves the list standing. Deleting a plan on a garbled call is the one mistake this tool can
 * make that the model cannot undo, because neither end kept a copy of what was there.
 */
public final class Todos {

    /** The three statuses, and the vocabulary the answer speaks back in. */
    private static final String PENDING = "pending";
    private static final String DOING = "in_progress";
    private static final String DONE = "completed";

    /**
     * WHAT THE MODEL IS TOLD, AND THE WHOLE-LIST RULE IS THE FIRST SENTENCE OF IT. A model that
     * reads this as an append tool sends one item and deletes the rest, so the deletion is stated
     * before anything else the tool can do.
     */
    private static final String DESCRIPTION = """
            Record the task list for this run. Send the COMPLETE list on every call: this is a \
            whole-list write, the last one wins, and any item left out of it is deleted. Statuses \
            are pending, in_progress and completed. Keep one item in_progress at a time, and mark \
            an item completed as soon as it is done rather than in a batch at the end. The answer \
            is the list exactly as it was recorded.""";

    /**
     * FIELD NAMES ARE snake_case BECAUSE THAT IS WHAT MODELS ARE TRAINED AGAINST — {@code todos},
     * {@code content}, {@code status}. The enum is advertised as well as described, because the
     * three statuses are the only part of this schema a model can get wrong in a way that changes
     * what the list means.
     */
    private static final String SCHEMA = """
            {"type":"object","properties":{"todos":{"type":"array",\
            "description":"The complete list, in the order it should be worked. Items left out of \
            this array are deleted.","items":{"type":"object","properties":{\
            "content":{"type":"string","description":"What is to be done, as one imperative line."},\
            "status":{"type":"string","enum":["pending","in_progress","completed"],\
            "description":"pending, in_progress or completed."}},\
            "required":["content","status"]}}},"required":["todos"]}""";

    private static final Tool WRITE = new Tool("todo_write", DESCRIPTION, SCHEMA);

    /** One line of the list. Two strings, because two strings is all the schema advertises. */
    private record Item(String content, String status) {
    }

    /** The last thing the model said, whole. Replaced by {@link #write}, read by {@link #current}. */
    private volatile List<Item> held = List.of();

    /**
     * The one tool, ready to be merged into whatever else an agent is given.
     *
     * <p>A map of one entry rather than a bare {@link Tool} so that this composes with every other
     * tool holder in the package by {@code putAll}, and so that adding a second tool here later is
     * not a signature change for every caller.
     */
    public Map<Tool, Calling> tools() {
        return Map.of(WRITE, this::write);
    }

    /**
     * THE LIST AS TEXT, FOR A CALLER THAT IS SHOWING IT RATHER THAN A MODEL THAT WROTE IT.
     *
     * <p>Empty when nothing is held, deliberately: a caller putting this in a status line wants
     * nothing on the screen before the first write, not the word "none". The heading — how many are
     * done, whose list it is — belongs to whoever is drawing the screen, and one invented here would
     * be a second heading beside theirs. Bounded by {@link Retain#most}, so a model that recorded
     * four hundred items cannot make a caller's log unreadable.
     */
    public String current() {
        return Retain.most(render(held));
    }

    /**
     * ONE WRITE, WHICH IS ALWAYS THE WHOLE LIST.
     *
     * <p>It cannot fail. Every way the arguments can be wrong ends in a list and a sentence naming
     * what was done with the call — recorded, recorded in part, or not recorded at all — because
     * the only other move available under this contract is to throw away a plan the model may have
     * spent several turns building.
     */
    private String write(Called call) {
        String arguments = call.arguments();
        String array = Json.part(arguments, "todos");
        // A NESTED VALUE THAT ARRIVES RE-ENCODED IS STILL THE LIST. Some OpenAI-compatible servers
        // hand the array back as a string with the JSON escaped inside it. Json.read unescapes,
        // and what falls out of it is the array this was always meant to be.
        if (array.startsWith("\"")) {
            array = Json.read(arguments, "todos");
        }
        // Json.part returns nothing for an array that never closes, which is what a generation
        // stopped mid-list looks like. The scanner below reads whole objects and ignores an
        // unterminated tail, so scanning from the opening bracket keeps the items that arrived.
        boolean whole = array.startsWith("[");
        if (!whole) {
            int opens = arguments.indexOf('[');
            array = opens < 0 ? "" : arguments.substring(opens);
        }
        if (array.isEmpty()) {
            return over("todo_write records the whole list and this call carried no todos array, so "
                    + "the list is unchanged. Send every item, as "
                    + "{\"todos\":[{\"content\":\"...\",\"status\":\"pending\"}]}. The arguments "
                    + "were: " + Retain.glance(arguments));
        }
        List<Item> next = new ArrayList<>();
        int contentless = 0;
        int restated = 0;
        for (String object : objects(array)) {
            String content = Json.read(object, "content").trim();
            if (content.isEmpty()) {
                contentless++;
                continue;
            }
            String status = Json.read(object, "status").trim().toLowerCase(Locale.ROOT);
            if (!PENDING.equals(status) && !DOING.equals(status) && !DONE.equals(status)) {
                restated++;
                status = PENDING;
            }
            next.add(new Item(content, status));
        }
        // A CALL THAT RECORDED NOTHING IS ONLY OBEYED WHEN IT ASKED FOR NOTHING. See the class
        // comment: "todos":[] is an instruction, and a call whose items were all unreadable is a
        // malformed generation that would otherwise delete a plan neither end has a copy of.
        if (next.isEmpty() && !emptied(whole, array)) {
            return over("Nothing in that call was a todo, so the list is unchanged"
                    + (contentless == 0 ? ". " : " — " + count(contentless, "item")
                            + " arrived with no content. ")
                    + "Each item is an object of content and status: "
                    + "{\"todos\":[{\"content\":\"...\",\"status\":\"pending\"}]}. Send "
                    + "\"todos\":[] to empty the list on purpose. The arguments were: "
                    + Retain.glance(arguments));
        }
        held = List.copyOf(next);
        return over(answer(whole, contentless, restated));
    }

    /**
     * Whether the arguments carried a complete array with genuinely nothing in it, which is the one
     * way to empty the list — as against an array this could read nothing out of, which is not.
     */
    private static boolean emptied(boolean whole, String array) {
        return whole && array.length() >= 2 && array.endsWith("]")
                && array.substring(1, array.length() - 1).isBlank();
    }

    /**
     * A sentence, with the list it left standing under it, bounded.
     *
     * <p>EVERY ANSWER CARRIES THE WHOLE LIST, INCLUDING THE ONES THAT CHANGED NOTHING. A model just
     * told that its call was unreadable needs to know what is still held before it composes the next
     * one; telling it only what went wrong invites a rewrite from memory, and under last-write-wins
     * a rewrite from memory is how items disappear.
     */
    private String over(String said) {
        String rendered = render(held);
        return Retain.most(rendered.isEmpty() ? said : said + "\n" + rendered);
    }

    /**
     * WHAT THE WRITE DID, IN COUNTS, WITH THE LIST ITSELF ADDED BY {@link #over}.
     *
     * <p>The counts lead because they are the part a model can act on without re-reading anything:
     * a number smaller than the number of items it sent is a number of items it has just lost, and
     * that difference is invisible in a rendered list it has never seen before.
     */
    private String answer(boolean whole, int contentless, int restated) {
        List<Item> list = held;
        int doing = howMany(list, DOING);
        StringBuilder said = new StringBuilder(list.isEmpty()
                ? "The list is now empty and nothing is being tracked."
                : list.size() + " recorded: " + howMany(list, DONE) + " completed, " + doing
                        + " in progress, " + howMany(list, PENDING) + " pending.");
        if (!whole) {
            said.append(" The arguments carried no complete todos array — cut off mid-list, or the "
                    + "array under another name — so this is what could be read out of them. Send "
                    + "the whole list again if it is not what you meant.");
        }
        if (contentless > 0) {
            said.append(" Dropped ").append(count(contentless, "item"))
                    .append(" that carried no content.");
        }
        if (restated > 0) {
            said.append(" Recorded ").append(count(restated, "item"))
                    .append(" with an unrecognised status as pending.");
        }
        if (doing > 1) {
            said.append(" ").append(doing).append(" items are in_progress at once; one at a time is "
                    + "what makes this list say where you are.");
        }
        return said.toString();
    }

    /** The list, one item per line. Empty for an empty list, which is what {@link #current} wants. */
    private static String render(List<Item> list) {
        StringBuilder out = new StringBuilder();
        for (Item item : list) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(mark(item.status())).append(' ').append(item.content());
        }
        return out.toString();
    }

    /**
     * A CHECKBOX RATHER THAN THE STATUS SPELLED OUT, because a forty-item list reads as a shape at
     * a glance and as a wall of the word "pending" otherwise. The status words are still in the
     * counts above the list, which is where a model looks for them.
     */
    private static String mark(String status) {
        return switch (status) {
            case DONE -> "[x]";
            case DOING -> "[>]";
            default -> "[ ]";
        };
    }

    private static int howMany(List<Item> list, String status) {
        int found = 0;
        for (Item item : list) {
            if (item.status().equals(status)) {
                found++;
            }
        }
        return found;
    }

    /** {@code 1 item} or {@code 2 items}. The answer is prose a model reads, so it agrees. */
    private static String count(int many, String noun) {
        return many + " " + noun + (many == 1 ? "" : "s");
    }

    /**
     * THE TOP-LEVEL OBJECTS OF A JSON ARRAY, AS RAW SUBSTRINGS. The whole of the nesting this file
     * needed, and the reason it is here rather than in {@code Json}: descending into an array is a
     * step {@code Json.part} does not take, and only this tool has ever wanted it.
     *
     * <p>It counts braces, and it counts them outside strings — a todo reading
     * {@code fix the {} case} would otherwise close an object three characters early and hand back
     * a fragment. An object left open at the end of the input is not returned, which is exactly what
     * a generation that stopped mid-item should do: the items before it are complete and are kept,
     * the half-written one is not a todo yet, and {@link #answer} says the array was incomplete.
     */
    private static List<String> objects(String array) {
        List<String> out = new ArrayList<>();
        boolean inString = false;
        int depth = 0;
        int from = -1;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth++ == 0) {
                    from = i;
                }
            } else if (c == '}' && depth > 0 && --depth == 0) {
                out.add(array.substring(from, i + 1));
            }
        }
        return out;
    }
}
