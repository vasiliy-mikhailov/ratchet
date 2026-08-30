package tech.mikhailov.ratchet.llm;

/**
 * THE ENDPOINT ANSWERED WITH AN ERROR STATUS, AND THE STATUS IS ON THE EXCEPTION.
 *
 * <p>This replaces a judgement that used to need two separate checks. The previous client mapped
 * statuses onto a typed hierarchy — retriable, non-retriable — inside an {@code internal} package
 * this library would not depend on, with no promise the mapping had even run by the time a
 * STREAMING error surfaced. So {@link Retrying} tested the types AND the raw status, because either
 * might be the one carrying the answer.
 *
 * <p>A client this library owns has no such problem: it knows the status because it read it off the
 * response. One field, one check, and the classification in {@link Retrying#transportFailures()}
 * became a single line.
 *
 * <p>THE BODY IS KEPT. A 400 from a proxy and a 400 from the model server say completely different
 * things and only the body distinguishes them — which mattered here, where an unknown request field
 * is dropped silently by one hop and rejected by the next.
 */
import tech.mikhailov.ratchet.record.Retained;

public final class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String body;

    public Refused(int status, String body) {
        super("the endpoint refused the request: HTTP " + status
                + (body == null || body.isBlank() ? "" : " — " + shortened(body)));
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public int status() {
        return status;
    }

    /** What the server said, whole. The message carries only the first line of it. */
    public String body() {
        return body;
    }

    private static String shortened(String said) {
        String flat = said.strip().replaceAll("\\s+", " ");
        // IT SAYS HOW MUCH NOW. This wrote a bare ellipsis — the one marker here that told a
        // reader something was missing and refused to say how much, which is the censoring shape
        // that let a record's own bound go unmeasured for eight releases. body() keeps the whole
        // thing either way, so a magnitude is what makes that recoverable rather than a guess.
        return Retained.head(flat, 300).text();
    }
}
