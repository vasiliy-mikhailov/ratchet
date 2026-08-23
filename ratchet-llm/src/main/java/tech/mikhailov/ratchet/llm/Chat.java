package tech.mikhailov.ratchet.llm;

/**
 * SOMETHING THAT ANSWERS. One method, so everything in this package can decorate it.
 *
 * <p>This replaces a third party's interface of the same shape, and the reason it is worth owning
 * is not taste. That interface carried five methods, four of which every decorator in this package
 * had to forward without having an opinion about them — capabilities, listeners, default request
 * parameters — and each forwarding was a line that could only ever be wrong. More to the point, a
 * consumer who wanted {@link Retrying} or the schedule underneath it had to accept eight megabytes
 * of model client to get a function over a list of failures.
 *
 * <p>A TEST IMPLEMENTS THIS IN ONE LINE, which is what makes the guards in this package provable:
 * a flaky endpoint is {@code ask -> { throw new IOException(); }}, and the whole ten-attempt
 * schedule is asserted in milliseconds with no socket anywhere.
 */
@FunctionalInterface
public interface Chat {

    /**
     * Ask, and wait for the whole answer.
     *
     * <p>Blocking, though the transport underneath streams: streaming is how the liveness guard
     * gets to watch time-since-last-token instead of time-since-request, and it is not something a
     * caller composing a flow should have to think about.
     *
     * @throws RuntimeException on anything the endpoint or the transport did wrong. See
     *                          {@link Refused}, {@link GaveUp} and {@link Truncated}.
     */
    Reply answer(Ask ask);
}
