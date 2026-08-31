package tech.mikhailov.ratchet.tools;

import tech.mikhailov.ratchet.record.Retained;

/**
 * HOW MUCH OF A TOOL'S OWN OUTPUT GOES BACK, WHICH IS A DIFFERENT BOUND FROM THE RECORD'S.
 *
 * <p>{@code Recording} already bounds what a result carries into the prompt and keeps the whole
 * thing in the corpus. This is the bound a tool applies to ITSELF before that — a directory listing
 * of forty thousand entries, a {@code bash} call that printed a gigabyte — because a tool that hands
 * back everything and lets a later layer cut it has already paid for the string.
 *
 * <p>Both go through {@link Retained}, so the sentence a reader sees is the same one this library
 * uses everywhere else, and it says how much was left out.
 */
final class Retain {

    /**
     * SIXTEEN THOUSAND, WHICH IS dsh'S NUMBER FOR THE SAME JOB.
     *
     * <p>Their {@code str_replace_editor} retains 16,000 characters of a file or directory view.
     * Taking it rather than inventing one means a model that has met both sees the same shape, and
     * it is roughly twice what their watcher shows of a tool result — which is right, because this
     * is the tool's own output rather than a summary of it.
     */
    static final int MOST = 16_000;

    private Retain() {
    }

    /** {@code text}, bounded, saying how much it left out. */
    static String most(String text) {
        return Retained.head(text == null ? "" : text, MOST, "\n").text();
    }

    /** A short quotation of something that went wrong, for a message the model has to act on. */
    static String glance(String text) {
        return Retained.head(text == null ? "" : text, 200).text();
    }
}
