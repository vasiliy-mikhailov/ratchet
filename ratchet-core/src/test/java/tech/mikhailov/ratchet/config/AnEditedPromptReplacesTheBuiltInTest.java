package tech.mikhailov.ratchet.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AN EDIT ON DISK IS THE WHOLE PROMPT, AND EVERY OTHER OUTCOME IS SILENCE.
 *
 * <p>This store decides what every agent in a run is told. There are exactly two answers it may
 * give: the text somebody saved, entire, or nothing at all so the built-in stands. A third answer —
 * half a file, a file from the wrong variant, an exception thrown while the first agent is being
 * built — is not a wrong prompt, it is a run that either does something nobody asked for or does
 * not start. The class had 41 mutants and no tests when this was written, so none of that was
 * written down anywhere.
 *
 * <p>THREE REQUIREMENTS HERE WERE FOUND BY MUTATION AND NOT BY READING. First, the store is beside
 * the results and never inside them, and a harness handed a bare relative name like {@code out} has
 * a parent-less path, so the branch nobody exercises is the one that decides whether a run starts
 * at all. Second, a name that reaches this class off a URL must not be able to name a file outside
 * the store, and the guard that stops it is one regex with no test behind it. Third, saving under a
 * name the store refuses has to fail loudly: a save that returned quietly would tell a person their
 * edit was kept while every run kept using the built-in.
 */
class AnEditedPromptReplacesTheBuiltInTest {

    @Test
    void theSavedTextIsHandedBackWholeAndTheBuiltInIsNotConsulted(@TempDir Path root)
            throws IOException {
        // Leading blank line and trailing newline are what a text editor leaves behind. There is no
        // merge and no tidying: the file IS the prompt, or a person reading the settings page and a
        // person reading the transcript are reading two different prompts.
        String edited = "\nYou are the doer.\n  Keep the diff small.\n";

        Prompts.save(root, "doer", "v1", edited);

        assertEquals(edited, Prompts.override(root, "doer", "v1"),
                "the agent is told exactly what was saved, byte for byte");
        assertTrue(Prompts.edited(root, "doer", "v1"),
                "and the page that offers a revert must be able to see there is something to revert");
        assertEquals(List.of("doer"), Prompts.editedOn(root, "v1"),
                "one edit, listed under the name of the agent it belongs to");
    }

    @Test
    void theSameAgentUnderAnotherVariantIsStillTheCodesOwnPrompt(@TempDir Path root)
            throws IOException {
        Prompts.save(root, "doer", "v1", "the rules that only hold under v1");

        assertEquals("", Prompts.override(root, "doer", "v2"),
                "an edit written against one set of conditions must not be handed to a run that "
                        + "cannot meet them");
        assertFalse(Prompts.edited(root, "doer", "v2"),
                "and the other variant's page must not offer to revert an edit it does not have");
        assertTrue(Prompts.editedOn(root, "v2").isEmpty(),
                "nor count it: " + Prompts.editedOn(root, "v2"));
    }

    @Test
    void thePlatformInTheNameMakesItADifferentAgentWithADifferentEdit(@TempDir Path root)
            throws IOException {
        Prompts.save(root, "doer@one", "v1", "build it the way one does");
        Prompts.save(root, "doer@other", "v1", "build it the way other does");

        assertEquals("build it the way one does", Prompts.override(root, "doer@one", "v1"),
                "the tail is part of the identity: the edit filed under one platform is the edit "
                        + "that platform's agent is told");
        assertEquals("build it the way other does", Prompts.override(root, "doer@other", "v1"),
                "fourteen agents differ only by the tail of their name; collapsing them hands "
                        + "every platform the last edit anybody made");
        assertEquals(List.of("doer@one", "doer@other"), sorted(Prompts.editedOn(root, "v1")),
                "and both are named in full, tail included, not truncated to the agent");
    }

    @Test
    void aBlankFileIsNotABlankPrompt(@TempDir Path root) throws IOException {
        // A save that went wrong, or a revert that half happened. An agent handed nothing to do
        // does something arbitrary, which is worse than doing the built-in thing.
        Prompts.save(root, "doer", "v1", "   \n\t\n  ");

        assertEquals("", Prompts.override(root, "doer", "v1"),
                "whitespace is not an instruction, so the built-in still stands");
        // WORTH WRITING DOWN, BECAUSE THE TWO ANSWERS DISAGREE: the file exists, so the store
        // reports the agent as edited while every run ignores what it says. A settings page reading
        // both will show "edited" over a prompt that is having no effect at all.
        assertTrue(Prompts.edited(root, "doer", "v1"),
                "the file is there, and a person needs to be able to revert it to get rid of it");
    }

    @Test
    void noStoreAtAllMeansThereAreNoEditsRatherThanAFailedRun(@TempDir Path root) {
        // The agent may run in a container that never had the store mounted. "I cannot see the
        // overrides" has to mean "there are none", because the alternative is an exception thrown
        // while the run is being built, before anything has been done that could be resumed.
        assertEquals("", Prompts.override(null, "doer", "v1"),
                "a missing store reads as no edits");
        assertFalse(Prompts.edited(null, "doer", "v1"),
                "and a store nobody can see holds no edits, rather than throwing at the page that "
                        + "asked");
        assertTrue(Prompts.editedOn(null, "v1").isEmpty(), "and lists nothing");
        assertEquals("", Prompts.override(root, "doer", null),
                "a request that names no variant asks for nothing, and gets nothing");
        assertTrue(Prompts.editedOn(root, null).isEmpty(),
                "the same for the listing behind the header count");
    }

    @Test
    void aVariantKeyThatIsNotAVariantKeyCannotNameADirectoryOutsideTheStore(@TempDir Path dir)
            throws IOException {
        // The key reaches this class off a URL. Without the check it is one path segment away from
        // reading anything the process can read and calling it a prompt.
        Path root = Files.createDirectories(dir.resolve("store"));
        Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(dir.resolve("elsewhere/doer.txt"), "text from outside the store");

        assertEquals("", Prompts.override(root, "doer", "../elsewhere"),
                "a key that climbs out of the store names nothing at all");
        assertFalse(Prompts.edited(root, "doer", "../elsewhere"),
                "and nothing outside the store is reported as an edit");
        assertTrue(Prompts.editedOn(root, "../elsewhere").isEmpty(),
                "nor listed: " + Prompts.editedOn(root, "../elsewhere"));
        assertEquals("", Prompts.override(root, "doer", "V1"),
                "real keys are lower-case letters, digits and hyphens; anything else is a caller "
                        + "doing something it should not");
    }

    @Test
    void anAgentNameThatIsNotAnAgentNameCannotReachAFileOutsideTheStore(@TempDir Path dir)
            throws IOException {
        Path root = dir.resolve("store");
        Files.createDirectories(root.resolve("v1"));
        Files.writeString(root.resolve("secret.txt"), "not a prompt");

        assertEquals("", Prompts.override(root, "../secret", "v1"),
                "the name comes off a URL, so a name with a slash or a dot-dot in it buys nothing");
        assertFalse(Prompts.edited(root, "../secret", "v1"),
                "and a file the store does not own is not reported as an edit somebody could "
                        + "revert, which would delete it");
        assertEquals("", Prompts.override(root, "Doer", "v1"),
                "every real name is lower-case letters, digits and hyphens");
    }

    @Test
    void savingUnderANameTheStoreRefusesFailsLoudlyRatherThanQuietly(@TempDir Path dir)
            throws IOException {
        Path root = dir.resolve("store");
        Files.createDirectories(root.resolve("v1"));
        Files.writeString(root.resolve("secret.txt"), "not a prompt");

        assertThrows(IOException.class, () -> Prompts.save(root, "../secret", "v1", "hijacked"),
                "a save that returned quietly would tell a person their edit was kept while every "
                        + "run went on using the built-in");
        assertEquals("not a prompt", Files.readString(root.resolve("secret.txt")),
                "and nothing outside the store was written on the way to refusing");
    }

    @Test
    void revertingLeavesTheBuiltInStandingBecauseItWasNeverGone(@TempDir Path root)
            throws IOException {
        Prompts.save(root, "doer", "v1", "an edit somebody regretted");

        Prompts.revert(root, "doer", "v1");

        assertEquals("", Prompts.override(root, "doer", "v1"),
                "the next run that starts is told what the code says again");
        assertFalse(Prompts.edited(root, "doer", "v1"), "and nothing offers to revert it twice");
        assertTrue(Prompts.editedOn(root, "v1").isEmpty(),
                "the header counts nothing: " + Prompts.editedOn(root, "v1"));
    }

    @Test
    void revertingAnEditThatCouldNeverHaveExistedIsNotAnErrorAndDeletesNothing(@TempDir Path dir)
            throws IOException {
        // The revert button is reached by the same URL the name came off, and revert is the one
        // operation here that DESTROYS something. A name the store would refuse to read from must
        // not be a name it will delete through: refusing it and then throwing turns a bad link into
        // a failed request, and honouring it turns a bad link into data loss outside the store.
        Path root = dir.resolve("store");
        Files.createDirectories(root.resolve("v1"));
        Files.writeString(root.resolve("secret.txt"), "not a prompt");

        assertDoesNotThrow(() -> Prompts.revert(root, "../secret", "v1"),
                "there was nothing to delete, and that is the whole answer");
        assertTrue(Files.exists(root.resolve("secret.txt")),
                "a name that climbs out of the store must not be a name revert deletes through");
        assertDoesNotThrow(() -> Prompts.revert(root, "doer", "v1"),
                "nor is reverting an agent that was never edited");
    }

    @Test
    void oneEditIsWrittenWholeOrNotAtAllAndLeavesNothingBehind(@TempDir Path root)
            throws IOException {
        Prompts.save(root, "doer", "v1", "first");

        Prompts.save(root, "doer", "v1", "second");

        assertEquals("second", Prompts.override(root, "doer", "v1"),
                "a save replaces the edit rather than adding to it");
        assertEquals(List.of("doer.txt"), sorted(namesIn(root.resolve("v1"))),
                "the file is staged beside and renamed over, so an agent reading it mid-write sees "
                        + "the old text or the new one and never a leftover half: "
                        + namesIn(root.resolve("v1")));
    }

    @Test
    void theHeaderCountsAgentsRatherThanFiles(@TempDir Path root) throws IOException {
        Prompts.save(root, "doer", "v1", "an edit");
        Prompts.save(root, "checker", "v1", "another");
        // Anything else that ends up in the directory — a note, a half-written save from a machine
        // that died between the write and the rename — is not an agent and must not be counted as
        // one, still less handed to a page as a name to link to.
        Files.writeString(root.resolve("v1/notes.md"), "why we edited these");
        Files.writeString(root.resolve("v1/doer.txt.staged"), "a save that never finished");

        assertEquals(List.of("checker", "doer"), sorted(Prompts.editedOn(root, "v1")),
                "two agents are edited, and the names come back without the extension");
        assertTrue(Prompts.editedOn(root, "never-used").isEmpty(),
                "a variant nobody has edited counts zero rather than failing the page");
    }

    @Test
    void theStoreSitsBesideTheResultsRatherThanInsideThem(@TempDir Path run) throws IOException {
        // The results directory is what a reader is served. A prompt is not a record of anything
        // that happened, and publishing one alongside the run's output would hand every reader the
        // instructions as though they were findings.
        Files.createDirectories(run.resolve("prompts/v1"));
        Files.writeString(run.resolve("prompts/v1/doer.txt"), "the edit beside the results");
        Files.createDirectories(run.resolve("results/prompts/v1"));
        Files.writeString(run.resolve("results/prompts/v1/doer.txt"), "the edit inside the results");

        Prompts.beside(run.resolve("results"));

        assertEquals("the edit beside the results", Prompts.override("doer", "v1"),
                "the store is the sibling of the results directory, not a child of it");
    }

    @Test
    void aResultsDirectoryGivenAsABareNameStillHasAStoreBesideIt() {
        // FOUND BY MUTATION. A harness invoked with a relative output directory — `ratchet out` —
        // hands over a path with no parent at all, and this is the first thing touched when the
        // run's agents are built. Getting it wrong is not a wrong prompt, it is a run that throws
        // before it has done anything a resume could pick up.
        assertDoesNotThrow(() -> Prompts.beside(Path.of("ratchet-bare-output-dir")),
                "a path with no parent is its own root, and the store hangs off it");
        assertEquals("", Prompts.override("doer", "v1"),
                "there are no edits under it, which is an answer rather than an exception");
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted().toList();
    }

    private static List<String> namesIn(Path dir) {
        try (var files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
