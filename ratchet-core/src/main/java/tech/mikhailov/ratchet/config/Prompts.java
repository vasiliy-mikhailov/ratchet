package tech.mikhailov.ratchet.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A PROMPT SOMEBODY EDITED, WHICH REPLACES THE BUILT-IN ENTIRELY.
 *
 * <p>THERE IS NO MERGE. An edit is the whole prompt or it is nothing, because a prompt half from the
 * code and half from a box is a prompt nobody can read in one place, and reading it in one place is
 * the only way anybody ever works out why an agent did what it did.
 *
 * <p>PER AGENT AND PER VARIANT. The same agent is a different agent under a different variant: it is
 * shown different rules and different limits, and the text a settings page shows is the text for the
 * variant being looked at. Storing one override for every variant would take an edit made against
 * one set of conditions and hand it to a run that cannot meet them.
 *
 * <p>IT TAKES EFFECT ON THE NEXT RUN THAT STARTS, not on the ones already going. Every agent in a
 * run is built once, at the top, from whatever was on disk then; a run that changed its own
 * instructions halfway would be a run nobody could reproduce.
 *
 * <p>The store sits beside the results rather than inside them: the results directory is what a
 * reader is served, and a prompt is not a record of anything that happened.
 *
 * <p>THE VARIANT ARRIVES AS A KEY AND NOTHING MORE. This files text under a name and hands it back;
 * what the name means is the caller's business, and a store that took whatever the caller's variants
 * are could not be lifted out of one program without that program's own vocabulary coming too. The
 * key every caller passes is the directory name the store has always used, so an override written
 * before this change still loads.
 */
public final class Prompts {

    /**
     * Where overrides live, set once by whoever knows the run root.
     *
     * <p>Static because the alternative is threading a path through every agent factory to serve a
     * feature most runs never use. It is written before any agent is built and read after, which is
     * the only ordering that matters. A library that makes a consumer set a global should say so out
     * loud, so: this is one, and it is the trade taken here knowingly.
     */
    private static volatile Path store = null;

    private Prompts() {
    }

    /** Point the store at a run root. {@code results} is the directory the harness was given. */
    public static void beside(Path results) {
        Path root = results.getParent() == null ? results : results.getParent();
        store = root.resolve("prompts");
    }

    /** The edited text for one agent under one variant, or empty when the code's own still stands. */
    public static String override(String agent, String variant) {
        return override(store, agent, variant);
    }

    /**
     * The same, against an explicit root.
     *
     * <p>Every rule about edits is here rather than in the static wrapper, so a test can state them
     * without a global. A null root reads as "no edits", because the agent may run in a container
     * that does not have the store mounted at all, and "I cannot see the overrides" must mean "there
     * are none" rather than a crash halfway through a run.
     */
    public static String override(Path root, String agent, String variant) {
        Path file = fileFor(root, agent, variant);
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            String text = Files.readString(file);
            // AN EMPTY FILE IS NOT AN EMPTY PROMPT. It is a save that went wrong or a revert that
            // half happened, and an agent given nothing to do does something arbitrary.
            return text.isBlank() ? "" : text;
        } catch (IOException unreadable) {
            return "";
        }
    }

    public static boolean edited(Path root, String agent, String variant) {
        Path file = fileFor(root, agent, variant);
        return file != null && Files.isRegularFile(file);
    }

    /**
     * Save an edit.
     *
     * <p>Written beside and renamed over, so an agent reading this file while it is being written
     * sees the old text or the new one and never half of each.
     */
    public static void save(Path root, String agent, String variant, String text) throws IOException {
        Path file = fileFor(root, agent, variant);
        if (file == null) {
            throw new IOException("no prompt store configured");
        }
        Files.createDirectories(file.getParent());
        Path staged = file.resolveSibling(file.getFileName() + ".staged");
        Files.writeString(staged, text, StandardCharsets.UTF_8);
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** Throw the edit away. The built-in is not restored; it was never gone. */
    public static void revert(Path root, String agent, String variant) throws IOException {
        Path file = fileFor(root, agent, variant);
        if (file != null) {
            Files.deleteIfExists(file);
        }
    }

    /** Every agent with an edit, for the count in the header. */
    public static List<String> editedOn(Path root, String variant) {
        List<String> out = new ArrayList<>();
        Path dir = dirFor(root, variant);
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".txt")) {
                    out.add(name.substring(0, name.length() - 4));
                }
            }
        } catch (IOException unreadable) {
            return out;
        }
        return out;
    }

    /**
     * {@code <root>/<variant>/<agent>.txt}, or null when there is nowhere to put it.
     *
     * <p>The agent name comes off a URL, so it is checked rather than trusted: a name with a slash
     * or a dot-dot in it would write outside the store. Every real name is lower-case letters and
     * hyphens, so anything else is a caller doing something it should not.
     *
     * <p>AND ONE OPTIONAL {@code @platform} TAIL, because fourteen of the agents inside one walk
     * existed once per platform and carried it in the name: {@code doer@one} is a different agent
     * from {@code doer@other}, handed different text and edited apart. Nothing else about the store
     * changes, which is the point of putting the platform in the name rather than adding a third
     * key to a path, a page and every lookup between them. The tail admits the same characters the
     * name does and no separator, so it still cannot leave the store.
     */
    private static Path fileFor(Path root, String agent, String variant) {
        Path dir = dirFor(root, variant);
        if (dir == null || !agent.matches("[a-z][a-z0-9-]*(@[a-z][a-z0-9-]*)?")) {
            return null;
        }
        return dir.resolve(agent + ".txt");
    }

    /**
     * The directory one variant of the pipeline files its edits under, or null when there is none.
     *
     * <p>The key is checked the way the agent name is, and for the same reason: both reach this
     * class off a URL, and either could otherwise name a directory outside the store. Digits and
     * hyphens is what every real key is, so anything else is a caller doing something it should
     * not.
     */
    private static Path dirFor(Path root, String variant) {
        if (root == null || variant == null || !variant.matches("[a-z0-9][a-z0-9-]*")) {
            return null;
        }
        return root.resolve(variant);
    }
}
