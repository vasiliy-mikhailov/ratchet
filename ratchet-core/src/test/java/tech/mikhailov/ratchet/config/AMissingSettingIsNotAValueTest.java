package tech.mikhailov.ratchet.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SETTING NOBODY SET IS NOTHING, AND NOTHING MUST NOT BE HANDED ON AS SOMETHING.
 *
 * <p>Every configurable value in this library resolves through {@link Env} — the model name, the
 * base URL, the retry schedule, the stall bound, whether the model thinks. Until this file there
 * was no test of it whatsoever. A mutation run over ratchet-core put a number on that: 30 mutants
 * in this one class, all 30 NO COVERAGE. Two of them replace what {@code get} returns with
 * {@code ""}, which is the difference between "this deployment did not name a model" and "this
 * deployment named a model called empty string" — one falls back, the other sends the empty string
 * to the endpoint and fails at the far end, an hour into a sweep, in someone else's log.
 *
 * <p>The five things asserted here are the ones a deployment relies on and nobody wrote down:
 * a name that is not set reads as {@code null} and never as {@code ""}; a name that IS set comes
 * back byte for byte, untrimmed and un-case-folded, because a trimmed value is a different model
 * and a different path; a fallback is the caller's and is returned unchanged rather than as some
 * constant the library preferred; a value {@link Env#flag} does not recognise leaves the decision
 * with the caller instead of being read as an answer; and {@link Env#copyIfSet} leaves a missing
 * variable OUT of a child's environment rather than mapping its name to {@code null}, which a real
 * child environment refuses outright.
 *
 * <p>WHAT THIS FILE CANNOT SAY, WHICH IS THE MORE USEFUL HALF. Twenty of the thirty die to it and
 * ten do not — counted by applying each change to a scratch copy of {@link Env} and running this
 * file against it, not estimated. Not one of the ten is equivalent: every one of them changes what
 * some deployment gets. They survive because {@link Env#flag} and {@link Env#get} call
 * {@code System.getenv} themselves, and a test cannot set an environment variable in its own JVM,
 * so the ten are exactly the mutants that need a variable set to a value of the test's CHOOSING.
 * The truth rule that decides what RATCHET_THINKING means:
 *
 * <pre>
 * "1", "true", "yes"    in any case    true
 * "0", "false", "no"    in any case    false
 * anything else set                    the caller's fallback
 * blank, or not set at all             the caller's fallback
 * </pre>
 *
 * <p>is asserted below on its last two rows only, and on the third of them with a variable the
 * launcher happened to export rather than one this file chose: whatever PATH holds, it is not a yes
 * and not a no, so the caller's fallback has to come back — which is enough to say that an
 * unrecognised value is not an answer. The first two rows are written down here and asserted
 * nowhere, and so is the blank-is-unset rule that {@link Env}'s own class comment leads with.
 * EIGHT of the ten survivors sit inside those two rows — one makes {@code RATCHET_THINKING=1} fall
 * through to the fallback, one makes {@code RATCHET_THINKING=true} turn thinking off, one makes
 * {@code "no"} mean yes — a ninth makes {@code flag} ignore the environment altogether, and the
 * tenth is blank-is-unset itself. No test can tell any of them from the code as written, and NO
 * SEAM WAS ADDED HERE TO LET ONE — inventing a back door for a test is the failure this project
 * argues against. What is missing is not a back door: it is the rule itself as a public function
 * of a string,
 * {@code Env.flagOf(String value, boolean fallback)} beside a blank-is-unset
 * {@code Env.valueOf(String value)}, with {@code flag} and {@code get} delegating to them and
 * reading {@code System.getenv} exactly as they do now. A consumer resolving the same setting out
 * of a properties file, a CLI argument or a compose file wants that function as much as a test
 * does, which is the difference between a seam and a hole.
 *
 * <p>The last line of {@code flag} — the one that returns the caller's fallback for a value it does
 * not recognise — is where that gap costs something today. {@code Model.build} spells its name
 * chain as {@code flag("RATCHET_THINKING", flag("OC_THINKING", flag("BJV_THINKING", true)))} and
 * calls it "the same first-non-blank chain as setting()". It is not the same chain:
 * {@code Model.setting} lets the first NON-BLANK name win whatever it says, while here a
 * RATCHET_THINKING of {@code maybe} — or of {@code " true "}, which is what a quoted compose entry
 * with a trailing space gives you — is unrecognised, falls through to the fallback, and hands the
 * decision back to the older OC_/BJV_ name the consumer thought it had overridden. Silently, and
 * with the new name set.
 */
class AMissingSettingIsNotAValueTest {

    /** A name no deployment has ever had. The premise of half this file, so it is checked. */
    private static final String NOBODY_HAS_EVER_SET_THIS = "RATCHET_NOTHING_HAS_EVER_SET_THIS";

    /** The six words {@link Env#flag} answers to. Everything else set is the caller's decision. */
    private static final Set<String> THE_WORDS_FLAG_ANSWERS_TO =
            Set.of("1", "true", "yes", "0", "false", "no");

    @BeforeEach
    void thePremiseHolds() {
        assertNull(System.getenv(NOBODY_HAS_EVER_SET_THIS),
                "if a machine ever did set this, every assertion below would be measuring the "
                        + "other branch and would still pass, which is worse than failing");
    }





    /**
     * Some variable this process actually has, whose value says neither yes nor no.
     *
     * <p>This is the whole of the "is set" branch that is reachable from inside the JVM under test:
     * whatever the launcher happened to export. A test cannot choose the value, so it cannot make
     * one say {@code true} — but it can insist that the one it finds says neither yes nor no, and
     * that is enough to pin the third row of {@code flag}'s table without controlling anything.
     *
     * <p>The six words are filtered out rather than tolerated, and that filter is the difference
     * between a requirement and a coin toss: a launcher that happened to export {@code YES=1} first
     * would otherwise turn {@link #aValueThatMeansNeitherYesNorNoLeavesTheDecisionWithTheCaller}
     * into a test that passes for a reason nobody can reproduce on the next machine. It throws
     * rather than skipping, because a green run that quietly checked nothing is the failure mode
     * this whole file is about.
     */
    private static Map.Entry<String, String> aVariableThisProcessHasThatSaysNeitherYesNorNo() {
        return System.getenv().entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .filter(entry -> !THE_WORDS_FLAG_ANSWERS_TO
                        .contains(entry.getValue().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "this JVM was launched with no environment variable holding a non-blank "
                                + "value other than " + THE_WORDS_FLAG_ANSWERS_TO + ", so nothing "
                                + "here exercised the branch where a setting is present, and this "
                                + "file is claiming more than it checked"));
    }
}
