package tech.mikhailov.ratchet.flow;

import java.io.IOException;
import java.util.List;

/**
 * THE ONE INTERFACE. A model call is one of these; so is a triad, so is a sequence, so is a walk
 * over a list, so is plain code that calls no model at all. They compose because they are the same
 * thing, and the composition is the shape of the program rather than a description of it kept in
 * step by hand.
 *
 * <p>{@code throws IOException} because the ones that are not model calls touch the workspace. A
 * lambda that cannot throw is still an Agent; the signature only has to permit it.
 *
 * <p>IT STANDS ALONE BECAUSE IT SAYS NOTHING ABOUT THE WORK. It was nested inside a thousand-line
 * catalogue of one domain's prompts, so {@link Flow}, {@link Shape} and {@link Reply}, none of
 * which knows what that domain is, could not be read, moved or reused without the catalogue coming
 * with them. Three names and no domain: run something, say what to call it, say what it contains.
 */
@FunctionalInterface
public interface Agent {

    String run(String task) throws IOException;

    /**
     * What to call this node in a trace or a picture. Empty for a leaf nobody needs to point at.
     */
    default String name() {
        return "";
    }

    /**
     * The nodes this one contains, in the order it reaches them.
     *
     * <p>THIS IS WHAT MAKES A DIAGRAM IMPOSSIBLE TO GET WRONG. A picture walked off this cannot
     * point at two stages that never joined, because it has no coordinates of its own: it is the
     * program, printed. The one this replaces drew an arc between array positions three and four,
     * and went on drawing it after the array stopped being what it had been.
     */
    default List<Agent> inside() {
        return List.of();
    }
}
