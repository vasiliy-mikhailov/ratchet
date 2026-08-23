package tech.mikhailov.ratchet.llm;

/**
 * WHAT ONE TOOL DOES WHEN THE MODEL CALLS IT.
 *
 * <p>One argument, where the interface this replaces took two. The second was a conversation id
 * for a chat memory, and this library configures none — {@link Asking} builds a fresh two-message
 * conversation every time — so it was null on every call any consumer ever made, and a parameter
 * that is always null is a parameter that only ever gets passed wrongly.
 *
 * <p>A TOOL THAT THROWS IS ANSWERED TO THE MODEL, not propagated: {@link Asking} catches it and
 * hands the message back as that call's result, so the conversation carries on. Returning a written
 * sentence instead is still the better habit, because a sentence composed for a reader beats
 * whatever a stack trace's first line happens to say.
 */
@FunctionalInterface
public interface Calling {

    String run(Called call);
}
