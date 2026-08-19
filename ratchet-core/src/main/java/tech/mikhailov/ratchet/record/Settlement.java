package tech.mikhailov.ratchet.record;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The last word per run, in a file a reader can open without replaying anything.
 *
 * <p>{@code trace.jsonl} answers "how"; {@code settlements.jsonl} answers "what happened". One row
 * per note, append-only, latest row wins: rewriting in place would lose the path a run took through
 * its states, and that path is half of what a reader came for.
 */
public final class Settlement {

    private Settlement() {
    }

    static void note(Path file, String key, String state, String because) {
        note(file, key, state, because, false, false);
    }

    static void note(Path file, String key, String state, String because,
                     boolean beforeOk, boolean afterOk) {
        note(file, key, state, because, beforeOk, afterOk, "");
    }

    /**
     * The same row, carrying which pipeline produced it.
     *
     * <p>A sweep runs for a fortnight and the harness changes daily, so a settled row without this
     * cannot be told apart from a settled row produced by different code. The fields arrive already
     * composed, WITHOUT a leading comma, because what names a pipeline is the pipeline's own
     * business and hashing it is {@link Digest}'s.
     */
    public static void note(Path file, String key, String state, String because,
                     boolean beforeOk, boolean afterOk, String version) {
        write(file, key, state, because, beforeOk, afterOk, version, "");
    }

    /**
     * The same row, saying whether the run that wrote it started fresh or picked up a killed one.
     *
     * <p>A RESUMED RUN IS A DIFFERENT TRIAL. It carries a different budget history and possibly a
     * different order of work, so a corpus comparison has to be able to exclude it, and this is the
     * field that lets it.
     *
     * <p>ON THE SETTLED ROW AND NOT ON A PROGRESS NOTE, which is why it is a second method rather
     * than an argument to the one above. A progress note says where a run is up to; it is not a
     * trial and has nothing to be excluded from, and a note that carried the field would have to
     * answer for every row a resumed run writes on its way through.
     */
    public static void settled(Path file, String key, String state, String because,
                     boolean beforeOk, boolean afterOk, String version, boolean resumed) {
        write(file, key, state, because, beforeOk, afterOk, version,
                ",\"resumed\":" + resumed);
    }

    private static void write(Path file, String key, String state, String because,
                     boolean beforeOk, boolean afterOk, String version, String extra) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // THE FIELD NAMES ARE THE CONSUMER'S WIRE FORMAT, NOT THIS LIBRARY'S TASTE. The first
            // consumer greps this row out of a shell launcher and reads "baseline" and "gate" by
            // name out of its own dashboard, and bash re-reads a running script by byte offset, so
            // the launcher cannot be corrected while it is up. Rename the parameters as much as you
            // like; the strings below are a format, and generalising them into a caller-named map
            // is the next version's change, gated on that project's own lanes test.
            String row = "{\"at\":\"" + System.currentTimeMillis() + "\",\"bump\":\"" + escape(key)
                    + "\",\"state\":\"" + escape(state) + "\",\"because\":\"" + escape(because)
                    + "\",\"baseline\":" + beforeOk + ",\"gate\":" + afterOk + extra
                    + (version.isEmpty() ? "" : "," + version) + "}\n";
            Files.writeString(file, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("settlement: " + e.getMessage());
        }
    }

    /** JSON string escaping, shared by every writer here so there is exactly one. Two escapers
     *  eventually disagree about a control character, and the field that shows it is a trace body,
     *  which is the field most likely to contain one. */
    public static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
