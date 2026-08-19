package tech.mikhailov.ratchet.record;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * THE HASH EVERY ROW IS FINGERPRINTED WITH, so that rows written months apart stay comparable.
 *
 * <p>A sweep runs for a fortnight and the harness changes daily. On one day of one sweep it was
 * deployed seven times, and three generations of prompt were live at once, because a running lane
 * keeps the image it started with. A settled row that says what happened and nothing about what
 * produced it cannot be told apart from a row produced by different code, so a pipeline stamps a
 * short fingerprint of what its agents were actually handed onto every row it writes.
 *
 * <p>WHAT GOES INTO THE FINGERPRINT IS THE PIPELINE'S BUSINESS. HASHING IT IS NOT. The text crosses
 * the boundary, never a digest: an implementation that hashed on its own side could return one of a
 * different length, or of a different algorithm, and every row on disk is comparable only because
 * all of them were hashed the same way. That argument used to hold inside one program; it now spans
 * two artifacts, which is exactly why the hashing has to live in the library rather than in each
 * consumer.
 *
 * <p>Short and stable. Eight hex characters is enough to group a fortnight of runs, and it is what
 * every row already written carries, so this cannot be widened without making the old rows
 * incomparable with the new ones.
 */
public final class Digest {

    private Digest() {
    }

    /** SHA-256, first four bytes, eight lower-case hex characters. Empty when it cannot be taken. */
    public static String of(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                b.append(String.format("%02x", d[i]));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // A fingerprint nobody can compute is a row without one, never a run that failed.
            return "";
        }
    }
}
