package tech.mikhailov.ratchet.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * THE SIX WORDS DECIDE AND NOTHING ELSE DOES: {@code 1/true/yes} and {@code 0/false/no} take the
 * answer away from the caller whichever fallback the caller passed, and every other value —
 * unset, blank, or a word the rule does not know — leaves the caller's fallback where it was.
 *
 * <p>{@link Env#flag} is the only place in this library that decides what "yes" means, and
 * {@code Model.build} spends that decision on thinking:
 * {@code flag("RATCHET_THINKING", flag("OC_THINKING", flag("BJV_THINKING", true)))}. Thinking
 * defaults to ON, so the no row is the whole of a deployment's ability to turn it off, and the
 * unrecognised row is what quietly hands the decision back to an older name the consumer
 * thought it had overridden. {@link AMissingSettingIsNotAValueTest} pinned the ends of that
 * table — unset, and a value the rule does not know — and left the middle of it, the six words
 * themselves, asserted nowhere.
 *
 * <p>WHY THE MIDDLE WAS LEFT. {@code flag} and {@code get} call {@code System.getenv} themselves,
 * and a JVM cannot set an environment variable on itself, so every remaining mutant in this class
 * needs a variable set to a value the test chose. Both ways round that are worth trying were
 * tried, and neither is open here. Reflection into {@code java.lang.ProcessEnvironment} — the
 * usual trick, against {@code theUnmodifiableEnvironment} or against
 * {@code Collections$UnmodifiableMap.m} — throws {@code InaccessibleObjectException} on JDK 21
 * and on JDK 26 alike, because java.base opens neither {@code java.lang} nor {@code java.util}
 * to the unnamed module and this build sets no {@code --add-opens}: "module java.base does not
 * opens java.lang to unnamed module", on both. A child JVM launched with a chosen environment
 * does run the real rule, but it proves nothing about a mutant — PIT mutates bytecode inside the
 * minion it runs tests in, so the child loads the class off the classpath unmutated and answers
 * the same whatever was done to it.
 *
 * <p>SO THIS FILE CHOOSES NOTHING, AND READS THE VALUES THIS JVM WAS LAUNCHED WITH AS A TABLE
 * NOBODY WROTE FOR A TEST. The rule is about values, and the process environment is a few dozen
 * of them; the four tests below sweep every variable rather than the first one the map hands
 * back, which is the difference between "the rule holds" and "the rule held for one sample, and
 * the next machine's first entry is a different sample".
 *
 * <p>AND BECAUSE THE MACHINE SUPPLIES THE TABLE, THE MACHINE CAN SUPPLY AN EMPTY ONE. A sweep
 * that finds no row to assert on passes, and a pass is how this file would say "the six words
 * decide" on a box where it checked none of them — the same silent green the file beside this one
 * exists to argue against. So the six-words test counts its rows first and ABORTS when there are
 * none: on a plain machine it lands in the report as not run, with the reason, rather than as a
 * fourth pass. It is an abort and not a failure because a red build on every launcher that did
 * not happen to export a yes is a test demanding the environment be arranged for it, which is the
 * thing this file refuses to do.
 *
 * <p>WHAT THAT IS WORTH, MEASURED RATHER THAN CLAIMED — and it is worth less than the count
 * suggests, which is the point of writing it down. Env has 28 mutants; 18 die to the file beside
 * this one and 10 live. Applying each of those 10 to a scratch copy of {@link Env} and running
 * this file against it, from the shell an agent runs the build in: EIGHT die and two live. They
 * die because this launcher happens to export {@code MallocNanoZone=0}, {@code GIT_EDITOR=true},
 * {@code SHLVL=1}, {@code CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES=false} and three variables set to
 * blank. THOSE EIGHT KILLS BELONG TO THE LAUNCHER AND NOT TO THIS FILE: run the same measurement
 * again in an environment of four variables — PATH, HOME, LANG, JAVA_HOME — and this file still
 * passes, all ten mutants live, and the mutation score has moved by a machine rather than by a
 * suite. Nothing in this process's environment holds {@code yes} or {@code no}, so those two rows
 * are unpinned here and would be unpinned anywhere it was not arranged in advance.
 *
 * <p>WHAT WOULD ACTUALLY RETIRE THEM is the seam {@link AMissingSettingIsNotAValueTest} already
 * argued for and this file is the evidence for: the rule as a public function of a string,
 * {@code Env.flagOf(String value, boolean fallback)} beside a blank-is-unset
 * {@code Env.valueOf(String value)}, with {@code flag} and {@code get} reading
 * {@code System.getenv} exactly as they do now and delegating the deciding. That is not a back
 * door cut for a test: a consumer resolving the same setting out of a properties file, a CLI
 * argument or a compose entry needs the same six words to mean the same thing, and today it has
 * to copy them. Until it exists, a green run of this file is not evidence that
 * {@code RATCHET_THINKING=0} turns thinking off — it is evidence that whoever launched the JVM
 * happened to export a zero.
 */
class OnlyTheSixWordsTakeTheDecisionAwayFromTheCallerTest {

    /** The three the rule reads as yes. Matched case-insensitively, as {@link Env#flag} does. */
    private static final Set<String> THE_THREE_WORDS_THAT_MEAN_YES = Set.of("1", "true", "yes");

    /** And the three it reads as no. Together these six are the whole of the environment's say. */
    private static final Set<String> THE_THREE_WORDS_THAT_MEAN_NO = Set.of("0", "false", "no");

    /** Distinctive on purpose: a fallback that comes back changed says where it was changed. */
    private static final String A_FALLBACK_NO_LAUNCHER_WOULD_EXPORT = "fallback/not-from-the-env";

    private List<Map.Entry<String, String>> theVariablesThisJvmWasLaunchedWith;

    @BeforeEach
    void thereIsAnEnvironmentToReadAtAll() {
        // The premise of every loop below. A JVM with an empty environment would run all four
        // tests, assert nothing in any of them, and report four passes — which is the one result
        // this file must not be able to produce.
        theVariablesThisJvmWasLaunchedWith = System.getenv().entrySet().stream()
                .filter(variable -> variable.getKey() != null && !variable.getKey().isBlank())
                .filter(variable -> variable.getValue() != null)
                .toList();

        assertFalse(theVariablesThisJvmWasLaunchedWith.isEmpty(),
                "this JVM reports no named environment variables at all, so every assertion in "
                        + "this file ran zero times and passed for that reason");
    }

    @Test
    void everyVariableThisProcessHasReadsBackAsItsOwnValueAndABlankOneReadsAsNothing() {
        for (Map.Entry<String, String> variable : theVariablesThisJvmWasLaunchedWith) {
            String name = variable.getKey();
            String value = variable.getValue();

            if (value.isBlank()) {
                assertNull(Env.get(name),
                        name + " is exported with a blank value, which is how a launcher writes "
                                + "\"I did not decide this\" — an unset variable and one set to "
                                + "\"\" are the same non-decision. Returning it as a value makes "
                                + "it beat every fallback behind it: Model.setting stops at the "
                                + "first non-null, so a blank RATCHET_MODEL would end the chain "
                                + "instead of falling through to the OC_ name that is actually "
                                + "set, and the empty string goes to the endpoint as the model "
                                + "name");
            } else {
                assertEquals(value, Env.get(name),
                        name + " must read back as the value this process holds for it, byte for "
                                + "byte and under its own name: a value trimmed, case-folded or "
                                + "taken from the wrong entry is a different model, a different "
                                + "path or a different token, and the deployment is looking at the "
                                + "variable it set");
            }
        }
    }

    @Test
    void theCallersFallbackStandsForExactlyTheNamesThatReadAsNothing() {
        for (Map.Entry<String, String> variable : theVariablesThisJvmWasLaunchedWith) {
            String name = variable.getKey();
            String value = variable.getValue();
            String whatTheCallerShouldSee =
                    value.isBlank() ? A_FALLBACK_NO_LAUNCHER_WOULD_EXPORT : value;

            assertEquals(whatTheCallerShouldSee,
                    Env.get(name, A_FALLBACK_NO_LAUNCHER_WOULD_EXPORT),
                    "the two-argument form is the one-argument form with the caller's value put "
                            + "where there is none, and the two must agree about " + name + ": a "
                            + "fallback that wins over a variable that is set is a setting nobody "
                            + "has, and a variable that wins while blank is a fallback nobody can "
                            + "reach");
        }
    }

    @Test
    void aValueTheRuleKnowsAnswersTheSameWhicheverFallbackTheCallerPassed() {
        // THE HALF THAT SAYS THE ENVIRONMENT DECIDES. Both fallbacks are passed to every variable,
        // deliberately: a flag asserted against one fallback only is satisfied by a flag that
        // ignores the environment and returns what it was given, which is exactly the mutant that
        // survives at Env line 28. Whether this loop reaches a row at all is the launcher's doing
        // and not this file's — see the class comment, which is where that is counted.
        List<Map.Entry<String, String>> theRowsThisMachineHappensToSupply =
                theVariablesThisJvmWasLaunchedWith.stream()
                        .filter(variable -> aWordTheRuleKnows(variable.getValue()))
                        .toList();

        // AND THIS IS WHERE IT IS SAID OUT LOUD RATHER THAN PASSED OVER. Measured: on a machine
        // whose environment is PATH, HOME, LANG and JAVA_HOME, the loop below runs zero times, and
        // without this line the test reports a pass for the requirement in this file's name having
        // checked none of it — all ten of Env's live mutants survive that pass. An abort puts it in
        // the surefire report as NOT RUN, which is what it is; a failure would make the build red
        // on every machine whose launcher was not arranged for this test, and that is worse.
        assumeFalse(theRowsThisMachineHappensToSupply.isEmpty(),
                "nothing in this JVM's environment is set to any of "
                        + THE_THREE_WORDS_THAT_MEAN_YES + " or " + THE_THREE_WORDS_THAT_MEAN_NO
                        + ", so the six words themselves were not exercised here at all. Nothing "
                        + "about Env is being reported as correct by this test on this machine — "
                        + "and until Env exposes the rule as a function of a string, nothing can: "
                        + "RATCHET_THINKING=yes turning thinking on is asserted nowhere in this "
                        + "library");

        for (Map.Entry<String, String> variable : theRowsThisMachineHappensToSupply) {
            String name = variable.getKey();
            String word = variable.getValue().toLowerCase(Locale.ROOT);

            if (THE_THREE_WORDS_THAT_MEAN_YES.contains(word)) {
                assertTrue(Env.flag(name, false),
                        name + " is set to " + variable.getValue() + ", which the rule reads as "
                                + "yes, so it must come back yes even though the caller's default "
                                + "was no — a deployment that turns thinking on and gets the "
                                + "library's own preference instead has no way to turn it on");
                assertTrue(Env.flag(name, true),
                        name + " reads as yes with a yes default too, which is the assertion that "
                                + "stops the row above being satisfied by a flag that inverts "
                                + "every fallback it is handed");
            } else if (THE_THREE_WORDS_THAT_MEAN_NO.contains(word)) {
                assertFalse(Env.flag(name, true),
                        name + " is set to " + variable.getValue() + ", which the rule reads as "
                                + "no, so it must come back no even though the caller's default "
                                + "was yes. RATCHET_THINKING defaults to on, so this is the whole "
                                + "of a deployment's ability to turn thinking off");
                assertFalse(Env.flag(name, false),
                        name + " reads as no with a no default too, for the same reason the yes "
                                + "row is asserted both ways round");
            }
        }
    }

    @Test
    void everyOtherValueLeavesBothAnswersWithTheCaller() {
        // THE HALF THAT SAYS THE CALLER DECIDES, and it is asserted over every variable rather
        // than over the first one the map hands back, because "the environment holds something
        // this rule does not know" is a statement about all of them: one variable saying so is one
        // sample, and the next machine's first entry is a different sample.
        for (Map.Entry<String, String> variable : theVariablesThisJvmWasLaunchedWith) {
            String name = variable.getKey();
            if (aWordTheRuleKnows(variable.getValue())) {
                continue;
            }

            assertTrue(Env.flag(name, true),
                    name + " is set to something the rule does not read as an answer (blank, or a "
                            + "word outside the six), so the caller's default stands — and here "
                            + "that default is on. Model.build spells its chain as "
                            + "flag(\"RATCHET_THINKING\", flag(\"OC_THINKING\", ...)), so this is "
                            + "the line that decides what a RATCHET_THINKING of \" true \" — a "
                            + "quoted compose entry with a trailing space — hands back");
            assertFalse(Env.flag(name, false),
                    name + " leaves an off default off as well: a value that means nothing to the "
                            + "rule cannot be what turns a setting on, and asserted one way round "
                            + "only, a flag that read every set value as yes would pass here");
        }
    }

    /**
     * Whether {@link Env#flag} would answer from this value rather than from the caller's fallback.
     *
     * <p>Case-folded exactly as {@code flag} folds it, and blank is deliberately not a word the
     * rule knows: an exported {@code ""} is the same non-decision as an unset name, so it belongs
     * on the caller's side of the split. The two halves of the table are asserted through this one
     * function so that they cannot drift into overlapping — a value counted as known by one test
     * and unknown by the other would let both pass while the rule underneath was wrong.
     */
    private static boolean aWordTheRuleKnows(String value) {
        String word = value.toLowerCase(Locale.ROOT);
        return THE_THREE_WORDS_THAT_MEAN_YES.contains(word)
                || THE_THREE_WORDS_THAT_MEAN_NO.contains(word);
    }
}
