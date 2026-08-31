package tech.mikhailov.ratchet.tools;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * THE ONE DIRECTORY EVERY PATH A MODEL WROTE IS RESOLVED AGAINST, AND THE ONLY CONFINEMENT IN THIS
 * MODULE.
 *
 * <p>It lives in its own class because two tool groups now enforce it — {@link Workspace} and
 * {@link Search} — and a confinement check written twice is a confinement check that will hold in
 * one of the two places after somebody edits it.
 *
 * <p>THE CHECK IS LEXICAL. Every path is resolved against the root and then normalised, so a
 * {@code ../..} is collapsed BEFORE the comparison rather than after it, and an absolute path is
 * taken as given and compared the same way. What does not start with the root is refused as a
 * result naming both paths, because a model told "denied" tries a variation of the same path and a
 * model told where the root is tries a path inside it.
 *
 * <p>WHAT IT DOES NOT DO, stated because a half-described guard is worse than an absent one. It
 * never touches the filesystem: a symlink INSIDE the root that points outside it is followed by
 * every tool here and this check will not see it. It bounds paths and not processes, so the
 * {@code bash} tool is unaffected and runs as whoever runs the JVM — see {@link Kit#withoutShell}
 * for the kit that does not offer one. And it is enforced by calls that come through here and by
 * nothing else. Real confinement is a container, a user, or a mount namespace; this is not a
 * smaller version of one.
 */
final class Rooted {

    private final Path root;

    Rooted(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("a workspace has to be rooted somewhere, and a null "
                    + "root would resolve every path a model wrote against the JVM's own working "
                    + "directory");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    /** Absolute and normalised once, at construction, because every check here compares to it. */
    Path path() {
        return root;
    }

    /** A resolved path, or the sentence explaining why the model is not getting one. */
    Where at(String given) {
        Path resolved;
        try {
            resolved = root.resolve(given).normalize();
        } catch (InvalidPathException notAPath) {
            return new Where(null, "\"" + Retain.glance(given) + "\" is not a usable path on this "
                    + "system: " + notAPath.getReason() + ".");
        }
        if (!resolved.startsWith(root)) {
            return new Where(null, given + " resolves to " + resolved + ", which is outside this "
                    + "workspace. Everything readable and writable is under " + root + ", and a "
                    + "path may be relative to that root or absolute inside it.");
        }
        return new Where(resolved, null);
    }

    /** A path as a result should name it: relative to the root, and "." for the root itself. */
    String shown(Path path) {
        String relative = root.relativize(path).toString();
        return relative.isEmpty() ? "." : relative;
    }

    /** A resolved path, or a refusal. Exactly one of the two is present. */
    record Where(Path path, String refusal) {

        boolean refused() {
            return refusal != null;
        }
    }
}
