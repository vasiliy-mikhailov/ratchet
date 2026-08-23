package tech.mikhailov.ratchet.llm;

/**
 * THE MODEL SPENT THE BUDGET THINKING AND HAD NOTHING LEFT TO SAY.
 *
 * <p>Its own type because it is neither of the two things it would otherwise be mistaken for. It is
 * not a transport failure, so retrying the identical request costs another full budget to arrive at
 * the identical wall. And it is emphatically not SILENCE: {@link Insisting} exists to re-ask a model
 * that declined to answer, and a truncation handed to it is re-asked as though the model had nothing
 * to say, when what happened is that it was cut off mid-thought.
 *
 * <p>Measured on this project's own endpoint, one prompt, no reasoning budget, a 3,000-token cap:
 * 9,488 characters of reasoning, ZERO characters of content, {@code finish_reason: length}. Before
 * this existed the runtime returned that empty string as the agent's answer, and a consumer that
 * writes what the agent returned wrote nothing over a file that had something in it.
 */
public final class Truncated extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public Truncated(String because) {
        super(because);
    }
}
