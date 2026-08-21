package tech.mikhailov.ratchet.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.flow.Flow;

/**
 * A RUN GETS A WHILE RATHER THAN AS LONG AS IT LIKES, AND HANDING OVER IS NOT FAILING.
 *
 * <p>A sweep runs for a fortnight on a fixed number of lanes. Without a bound one subject holds a
 * lane for twenty hours and the queue behind it never moves; with a bound in the wrong place the
 * run is killed mid-stage and the work is lost. So the bound sits between stages, where everything
 * that landed is already committed, and the boundary is a settlement state rather than a death.
 *
 * <p>WHAT THESE PIN IS THE PART THAT WOULD GO WRONG QUIETLY: a number kept somewhere instead of
 * counted, a marker read at the wrong moment, and an account that tells a model it is racing a
 * clock.
 */
class ARoundEndsBetweenStagesTest {

    private static final String KEY = "owner/repo|abc123|17|21";

    /** The consumer's word for somebody asking for the work again from the start. */
    private static final String REQUEUED = "requeued";

    /** A settlement row in the shape the file already holds them. */
    private static void settle(Path settlements, String key, String state) {
        Settlement.note(settlements, key, state, "because", false, false, "");
    }

    /**
     * THE ROUND IS COUNTED OFF THE RECORD RATHER THAN KEPT ANYWHERE.
     *
     * <p>A stored counter would be a second copy of a fact these rows already carry, and two copies
     * of one fact drift. A launcher counting the same rows the same way gets the same answer, which
     * is what lets either side write a boundary row without a number crossing between them.
     */
    @Test
    void theRoundIsOneMoreThanTheBoundariesOnTheRecord(@TempDir Path dir) throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        assertEquals(1, round(settlements, dir).number(),
                "a run nobody has paused is in its first");

        settle(settlements, KEY, Round.PAUSED);
        settle(settlements, "other/repo|def456|17|21", Round.PAUSED);
        assertEquals(2, round(settlements, dir).number(),
                "somebody else's boundary is not this run's round");

        settle(settlements, KEY, "bumping");
        settle(settlements, KEY, Round.PAUSED);
        assertEquals(3, round(settlements, dir).number());
    }

    /**
     * AND THE COUNT BEGINS AGAIN WHEN SOMEBODY ASKS FOR THE WORK FROM THE START.
     *
     * <p>Counting every boundary a subject had ever had meant a subject paused three times last
     * week met its launcher's ceiling on the first round of a fresh attempt and was filed as having
     * run out, having spent nothing. That state is not a verdict about the subject and it reads as
     * one on a page.
     *
     * <p>The consumer this came from corrected its launcher and not its Java, so one side answered
     * three where the other answered one, about a number they both derive from the same rows. The
     * word arrives from the caller here so that cannot happen twice.
     */
    @Test
    void theCountBeginsAgainWhenSomebodyAsksForTheWorkFromTheStart(@TempDir Path dir) {
        Path settlements = dir.resolve("settlements.jsonl");
        settle(settlements, KEY, Round.PAUSED);
        settle(settlements, KEY, Round.PAUSED);
        settle(settlements, KEY, REQUEUED);
        settle(settlements, KEY, Round.PAUSED);

        assertEquals(2, round(settlements, dir).number(),
                "one boundary since the requeue, so the round starting now is the second");
        assertEquals(4, Round.of(settlements, KEY, dir, "").number(),
                "and a consumer that names no such word counts every boundary there ever was");
    }

    /**
     * AND A TORN LINE IS NOT A ROUND.
     *
     * <p>The file is appended to by a process that gets killed, so a half-written last line is the
     * normal case. A reader that threw on one would report no rounds at all, and a reader that
     * counted a substring would count a boundary that was never finished being written.
     */
    @Test
    void aHalfWrittenBoundaryIsNotCounted(@TempDir Path dir) throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        settle(settlements, KEY, Round.PAUSED);
        Files.writeString(settlements,
                "{\"at\":\"9\",\"bump\":\"" + KEY + "\",\"state\":\"pau",
                StandardOpenOption.APPEND);

        assertEquals(2, round(settlements, dir).number(), "one whole boundary and one stump");
    }

    /**
     * THE ROUND ENDS WHEN THE LAUNCHER SAYS SO AND NOT BEFORE, which is the only thing this process
     * knows about time.
     *
     * <p>There is no clock here and no budget either. Whoever launches the run owns both, and says
     * so by creating one file this process can see.
     */
    @Test
    void theRoundEndsWhenTheMarkerAppearsAndNotBefore(@TempDir Path dir) throws Exception {
        Path stop = dir.resolve("expiring").resolve("owner_repo");
        Round round = Round.of(dir.resolve("settlements.jsonl"), KEY, stop, REQUEUED);

        assertFalse(round.reached(), "nobody has asked this run to hand over");

        Files.createDirectories(stop);

        assertTrue(round.reached(), "and the same round now says the word arrived");
    }

    /** A round nobody is counting never ends, for a run built to be read rather than launched. */
    @Test
    void aRoundNobodyIsCountingNeverEnds() {
        assertFalse(Round.none().reached());
        assertEquals(0, Round.none().number(), "zero means nobody is counting, not round one");
    }

    /**
     * A ROUND BOUNDARY SAYS NOTHING TO AN AGENT ABOUT A CLOCK, and that is a property to assert
     * rather than a convention to remember.
     *
     * <p>The account lands in the trace, and the trace is fed back to the agents of the next round
     * in ranked lines. The written finding behind this library is that a model told it is racing a
     * clock produces garbage and gives up, so the words that would tell it one must not be in
     * there: no minutes, no budget, no rounds remaining, no hurry.
     */
    @Test
    void theBoundaryAccountTellsNobodyTheyAreRacingAClock(@TempDir Path dir) {
        String account = round(dir.resolve("settlements.jsonl"), dir)
                .account("the module walk, before core");

        for (String clock : List.of("minute", "hour", "budget", "clock", "time", "remaining",
                "quickly", "hurry", "deadline", "out of")) {
            assertFalse(account.toLowerCase(Locale.ROOT).contains(clock),
                    "the account an agent may read says '" + clock + "': " + account);
        }
        assertTrue(account.startsWith(Round.PAUSED + "\n"), account);
        assertTrue(account.contains("the module walk, before core"),
                "it does say where it stopped, which is what a reader came for: " + account);
    }

    /**
     * A BOUNDARY THROWN MID-WALK HAS TO REACH THE RUNNER, AND THAT IS AN ASSERTION RATHER THAN AN
     * INFERENCE.
     *
     * <p>A walk is where a round will most often end, because it is where the hours go, and it is
     * the deepest place a boundary is thrown from. Two broad catches exist around a model call in
     * this library. A third one added on this path would swallow the settlement, and the runner
     * would then read a paused run as a crash.
     *
     * <p>It sits here rather than beside the combinators because neither package owns it on its
     * own: the thing thrown is a round boundary and the thing that must not catch it is a walk.
     */
    @Test
    void aBoundaryThrownInsideTheWalkTravelsOutOfIt(@TempDir Path dir) {
        String account = round(dir.resolve("settlements.jsonl"), dir).account("web");
        List<String> visited = new ArrayList<>();
        Agent walk = Flow.each("", () -> List.of("core", "web", "app"), m -> m,
                m -> Flow.seq("module", Flow.code("platform", task -> {
                    if ("web".equals(m)) {
                        throw new Flow.Settled(account);
                    }
                    visited.add(m);
                    return m;
                })));

        Flow.Settled settled = assertThrows(Flow.Settled.class, () -> walk.run(""));

        assertEquals(Round.PAUSED, settled.account().split("\n", 2)[0],
                "the state the sweep files is the first line of the account it carried");
        assertEquals(List.of("core"), visited,
                "the items before it are done and the ones after it are not started");
    }

    /** The round a launcher would build: the record it counts off, and the word it waits for. */
    private static Round round(Path settlements, Path dir) {
        return Round.of(settlements, KEY, dir.resolve("expiring").resolve("owner_repo"),
                REQUEUED);
    }
}
