package tech.mikhailov.ratchet.record;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WHICH ROUND OF ITS BUDGET THIS RUN IS IN, AND WHETHER IT HAS BEEN ASKED TO STOP.
 *
 * <p>NOT THE TOOL LOOP'S ROUND. {@code ratchet-llm} counts rounds of asking one model, bounded at
 * twenty-five. This one is a slice of wall clock: a run gets a while, is asked to hand over, and
 * the next one picks the same workspace up. The two never meet in one file, and the word is the
 * consumer's own because it is on the wire.
 *
 * <p>THERE IS NO CLOCK IN THIS PROCESS AND NO BUDGET IN IT EITHER. Whoever launches the run owns
 * both. It says so by creating one file this process can see, and everything here is a reaction to
 * that file existing. Nothing an agent is handed mentions it, no tool reports it, and no prompt is
 * built from it: the written finding behind that is that a model told it is racing a clock produces
 * garbage and gives up, and the only way to honour it with certainty is for the model's side of the
 * process to have nothing to tell it.
 *
 * <p>THE NUMBER IS COUNTED RATHER THAN KEPT. A stored counter would be a second copy of a fact the
 * settlement rows already carry, and two copies of one fact drift. One {@link #PAUSED} row is
 * written per round that ended, by whichever side ended it, so the count of them plus one is the
 * round now starting. A launcher counting the same rows the same way gets the same answer, which is
 * what lets either side end a round without a number crossing between them, and which is why the
 * one judgement in the counting, what restarts it, is the caller's to state rather than this
 * library's to assume.
 *
 * <p>THE TWO PATHS ARE THE CALLER'S CONVENTIONS AND NOT THIS LIBRARY'S. Where the record lives and
 * where a launcher leaves its word are decisions a consumer already made, usually by naming a run
 * directory, so they arrive as paths rather than as a configuration this would have to grow.
 */
public final class Round {

    /**
     * A ROUND BOUNDARY, WHICH IS NOT A VERDICT AND NOT A REQUEUE.
     *
     * <p>A requeue was the tempting reuse and it is the opposite instruction: that is somebody
     * asking for the work to be done again FROM THE START, and resuming one would hand that person
     * back the state they were trying to discard. See {@link Resume}, which tells the two apart.
     */
    public static final String PAUSED = "paused";

    private final Path stop;
    private final int number;

    private Round(Path stop, int number) {
        this.stop = stop;
        this.number = number;
    }

    /**
     * The round this run is in, and where to look for the launcher's word.
     *
     * @param settlements the append-only record this run's boundaries are counted off
     * @param key         the run's own key, because the record is shared by the whole sweep
     * @param stop        the path a launcher creates to ask this run to hand over
     * @param restart     the consumer's word for somebody asking for the work again FROM THE
     *                    START, such as {@code requeued}. The count begins again at such a row,
     *                    for the reason in {@link #ended}. Empty when nothing restarts it.
     */
    public static Round of(Path settlements, String key, Path stop, String restart) {
        return new Round(stop, ended(settlements, key, restart) + 1);
    }

    /** A round that can never end, for a run built to be read rather than launched. */
    public static Round none() {
        return new Round(null, 0);
    }

    /**
     * WHETHER THE RUN HAS BEEN ASKED TO STOP. The only thing this process knows about time.
     *
     * <p>Read between stages and nowhere else. A stage in flight finishes: everything a stage lands
     * is committed as it lands, so the overshoot is one stage and the loss is nothing. Abandoning
     * mid-stage would lose the same work a resume reverts anyway, and it would need a check inside
     * the agent loop, which is one refactor away from being something the agent can see.
     */
    public boolean reached() {
        return stop != null && Files.exists(stop);
    }

    /** Which round this is, from one upwards. Zero when nobody is counting. */
    public int number() {
        return number;
    }

    /** The settlement account for a boundary, naming the stage that did not start. */
    public String account(String stage) {
        return PAUSED + "\nthe lane ended between stages, at " + stage
                + "; everything that landed is committed, and the checkout and the journal are kept"
                + " so the next lane continues from here while the pipeline is unchanged";
    }

    /**
     * How many rounds of this key have already ended, read off the record.
     *
     * <p>A REQUEUE STARTS THE COUNT AGAIN, which is a correction and not a refinement. Counting
     * every boundary a subject had ever had meant a subject paused three times last week met its
     * launcher's ceiling on the FIRST round of a fresh attempt and was filed as having run out,
     * having spent nothing. That state is explicitly not a verdict about the subject, and it reads
     * as one. The launcher this came from was corrected and its Java counter was not, so the two
     * sides disagreed about a number they both derive; here they cannot, because the word that
     * restarts the count arrives from the same caller that tells the launcher.
     *
     * <p>IN ROW ORDER, which is the whole of why this is a walk rather than a filter. A reset only
     * means anything against the rows that came before it.
     *
     * <p>The reading is lenient because the file is appended to by a process that gets killed, so a
     * torn line is the normal case rather than a fault; a row nothing can parse is a row that says
     * nothing, which is not a round. See {@link Settlement#rowsFor}.
     */
    private static int ended(Path settlements, String key, String restart) {
        int n = 0;
        for (var row : Settlement.rowsFor(settlements, key)) {
            String said = row.getOrDefault("state", "");
            if (restart != null && !restart.isEmpty() && restart.equals(said)) {
                n = 0;
            } else if (PAUSED.equals(said)) {
                n++;
            }
        }
        return n;
    }
}
