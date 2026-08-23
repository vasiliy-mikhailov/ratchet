package tech.mikhailov.ratchet.llm;

/**
 * THE CEILING, AS A TYPE, BECAUSE IT IS THE ONE FAILURE HERE THAT MUST NOT BE RETRIED.
 *
 * <p>Every other exit from a stream is a connection that stopped working, and a fresh connection is
 * the answer to those. This one is the opposite: it fires on a stream that IS producing and has
 * been for hours, and the whole point of it is to give the slot back. A retry would spend another
 * ceiling discovering that again.
 *
 * <p>A type rather than a message, so {@link Retrying} does not have to match on English. It was
 * nested inside the class that threw it; it is top level now because the class that threw it was a
 * wrapper around somebody else's client and there is no longer such a thing.
 */
public final class GaveUp extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public GaveUp(String because) {
        super(because);
    }
}
