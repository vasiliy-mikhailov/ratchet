package tech.mikhailov.ratchet.record;

import java.nio.file.Path;
import java.util.Map;

/**
 * DOES THIS ATTEMPT PICK UP AN UNFINISHED ONE, and the answer is no unless all four agree.
 *
 * <p>THE DECISION, WHICH USED TO LIVE IN THE CONSUMER. {@link tech.mikhailov.ratchet.flow.Flow}
 * offers a resumable node and {@link Journal} holds what it needs, so this library shipped the
 * whole mechanism of resuming and nothing that decided whether to. The rule then grew in the first
 * consumer, where the next consumer could not have it and could not be warned by it. A wrong resume
 * is the worst outcome this record has: the stages it skips are skipped against edits that are not
 * in this workspace, and the run is then judged on a workspace nobody built.
 *
 * <p>THE FOUR CLAUSES.
 *
 * <ul>
 *   <li>the journal recorded a completed stage, so there is something to pick up;
 *   <li>the last settled row says the run was interrupted rather than concluded;
 *   <li>the version that produced that row is the version running now;
 *   <li>the workspace stands where the journal left it.
 * </ul>
 *
 * <p>TWO WORDS MEAN INTERRUPTED AND ONE OF THEM IS NOT A REQUEUE. {@link Round#PAUSED} is a run
 * that reached the end of its round, which is the same workspace with the same journal and one more
 * round behind it, and this library writes that word itself. What a consumer calls a run that died
 * mid-sentence is the consumer's own word, so it arrives as {@code inFlight}. A requeue, whatever
 * it is called, is somebody asking for the work to be done again from the start, and it must not be
 * named here: resuming one would hand that person back exactly the state they were discarding.
 *
 * <p>THE VERSION CROSSES AS AN OPAQUE VALUE. This library must not learn what a version IS, because
 * that is prompts, models, images and lists, and every consumer has a different set. It is the same
 * string {@link Settlement#note} splices onto the row, so the string compared IS the string
 * recorded, and it is compared field by field rather than whole because the row carries the run's
 * own columns around it.
 */
public final class Resume {

    /** Fields of a composed version string. Values are escaped by the writer, so no quote is in one. */

    private final Path settlements;
    private final String key;
    private final String inFlight;

    private Resume(Path settlements, String key, String inFlight) {
        this.settlements = settlements;
        this.key = key;
        this.inFlight = inFlight == null ? "" : inFlight;
    }

    /**
     * Where to look and what this consumer calls a run that is still going.
     *
     * @param settlements the append-only record, shared by the whole sweep
     * @param key         the run's own key, checked on every row rather than assumed
     * @param inFlight    the consumer's word for a run that died mid-sentence, such as
     *                    {@code bumping}. {@link Round#PAUSED} is resumable without being named.
     */
    public static Resume of(Path settlements, String key, String inFlight) {
        return new Resume(settlements, key, inFlight);
    }

    /**
     * The four clauses, in the order that reads cheapest first.
     *
     * @param journal      what the killed attempt recorded, already replayed
     * @param workspaceSha where the workspace is now
     * @param version      what this process would put its name to, composed as the row records it
     */
    public boolean picksUp(Journal journal, String workspaceSha, String version) {
        if (journal.tree().isEmpty()) {
            return false;
        }
        Map<String, String> last = lastSettledRow();
        String said = last.getOrDefault("state", "");
        boolean interrupted = Round.PAUSED.equals(said)
                || (!inFlight.isEmpty() && inFlight.equals(said));
        if (!interrupted) {
            return false;
        }
        if (!sameVersion(last, version)) {
            return false;
        }
        return journal.standsOn(workspaceSha);
    }

    /**
     * The last thing the record said about this key, whole, or an empty row when it never has.
     *
     * <p>THE WHOLE ROW RATHER THAN ITS STATE, because two of the four clauses are read off it and
     * they must be read off the SAME row: the state of one row beside the version of another would
     * answer a question nobody asked.
     */
    private Map<String, String> lastSettledRow() {
        Map<String, String> last = Map.of();
        for (Map<String, String> row : Settlement.rowsFor(settlements, key)) {
            if (!row.getOrDefault("state", "").isBlank()) {
                last = row;
            }
        }
        return last;
    }

    /**
     * WHETHER THE ROW WAS WRITTEN BY THIS VERSION, field by field.
     *
     * <p>EMPTY AGAINST NON-EMPTY IS A DIFFERENCE, not a missing answer to be forgiven. Every row
     * written before a consumer started recording some part of its identity carries an empty one,
     * and reading that as agreement would resume across exactly the change that introduced the
     * check. Empty on both sides IS equality, which is what stops a host where the identity cannot
     * be read at all from calling every run a different version from itself.
     *
     * <p>THE FIELD NAMES COME FROM THE RUNNING SIDE, which is the side that knows what it puts its
     * name to. A fixed list here would be this library holding one consumer's idea of a version,
     * and it is also the clause that lets a row carry more than the version: a round number is
     * appended to the same row by the same writer, and it must not read as a version that moved or
     * nothing would ever resume.
     */
    /**
     * DOES THE STORED ROW CARRY THE VERSION THAT IS RUNNING NOW.
     *
     * <p>BOTH SIDES GO THROUGH ONE PARSER, which is the whole of the fix here. The row arrives
     * unescaped, because {@link Settlement#rowsFor} read it with {@link Json#row}. The version
     * arrives as the composed fragment the caller is about to write, escapes and all. Comparing
     * those two with a regex meant a value holding a quote could never match its own recorded self,
     * so the bump never resumed, silently and for ever. Wrapping the fragment in braces and handing
     * it to the same reader makes the two comparable by construction rather than by agreement.
     *
     * <p>A VERSION THAT NAMES NOTHING LOSES THE DIMENSION RATHER THAN REFUSING, which is deliberate
     * and is what {@code aResumeIsRefusedWhenTheRowWasWrittenByADifferentVersion} asserts in its
     * last line: a host that cannot stamp a build still resumes, because blank agrees with blank.
     * The cost is the asymmetry below, and it is the caller's to avoid: a caller holding a version
     * and passing an empty one gets a resume it did not check for. Pass what you have.
     *
     * <p>WHAT THIS STILL CANNOT SEE, said plainly because a caller will meet it eventually: fields
     * the running version has DROPPED. Only the names the running side offers are compared, so a
     * version that stops naming a field resumes across the change that removed it. Catching that
     * needs to know which columns of a row are version columns, and knowing that means learning the
     * consumer's domain, which is the one thing this class must not do. A caller who needs it
     * should keep the field set fixed and let a removed field go empty rather than absent.
     */
    private static boolean sameVersion(Map<String, String> row, String version) {
        Map<String, String> named = Json.row("{" + (version == null ? "" : version) + "}");
        for (Map.Entry<String, String> field : named.entrySet()) {
            if (!field.getValue().equals(row.getOrDefault(field.getKey(), ""))) {
                return false;
            }
        }
        return true;
    }
}
