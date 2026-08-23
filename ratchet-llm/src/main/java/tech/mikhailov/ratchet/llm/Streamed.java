package tech.mikhailov.ratchet.llm;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;

import tech.mikhailov.ratchet.config.Env;
import tech.mikhailov.ratchet.record.Trace;

/**
 * A BLOCKING {@link ChatModel} OVER A STREAMING ONE, so a long prompt cannot look like a dead one.
 *
 * <p>The runtime wants a blocking model; a request timeout on a blocking client is the wrong
 * instrument for this workload. A request here is not one generation: it is the whole accumulated
 * conversation re-prefilled, and that grows with every tool call the agent has already made. While
 * the server prefills, a non-streaming client sends nothing and receives nothing, so an idle
 * connection and a slow answer are indistinguishable to it and to every proxy between them. The
 * only way to make the wait finite is a total cap, and a total cap kills work that is progressing:
 * a survey that was working died at twelve minutes, and the client's own retry then spent the same
 * twelve minutes twice more on a prompt that was only ever going to get longer.
 *
 * <p>Streaming replaces that question with a better one. Tokens arrive as they are produced, so the
 * guard is TIME SINCE THE LAST TOKEN, not time since the request began. A generation that takes an
 * hour is never interrupted; a connection that has actually died is noticed in {@link #STALL}. That
 * is a liveness probe rather than a budget, which is the distinction this project keeps: never cap
 * the model's work, always notice when the work has stopped.
 *
 * <p>The reasoning is captured at the transport by {@link Reasoning}, not here: this server names
 * the field in a way the client does not read, so the handler's thinking callback never fires.
 */
public final class Streamed implements ChatModel {

    /**
     * THE LIVENESS GUARD OVER A STREAMING MODEL YOU ALREADY HAVE.
     *
     * <p>Public for the same reason {@link Retrying#on} is: a consumer with its own client wanted
     * the guard without the client. What it buys is the distinction this whole class exists for —
     * time since the last TOKEN rather than time since the request, so a long generation is never
     * cut and a dead socket is still noticed.
     */
    public static ChatModel over(StreamingChatModel streaming, Trace trace) {
        return new Streamed(streaming, trace, Watch.fromEnv());
    }

    /** The same, with the two bounds chosen rather than read from the environment at class load. */
    public static ChatModel over(StreamingChatModel streaming, Trace trace, Watch watch) {
        return new Streamed(streaming, trace, watch);
    }


    /**
     * THE CEILING, AS A TYPE, BECAUSE IT IS THE ONE FAILURE HERE THAT MUST NOT BE RETRIED.
     *
     * <p>Every other exit from {@link #doChat} is a connection that stopped working, and a fresh
     * connection is the answer to those. This one is the opposite: it fires on a stream that IS
     * producing and has been for hours, and the whole point of it is to give the slot back. A
     * retry would spend another ceiling discovering that again.
     *
     * <p>A type rather than a message, so {@link Retrying} does not have to match on English. The
     * other two exits stay {@link IllegalStateException}: they mean the same thing to a caller and
     * nothing yet needs to tell them apart.
     */
    public static final class GaveUp extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public GaveUp(String because) {
            super(because);
        }
    }

    /**
     * THE MODEL SPENT THE BUDGET THINKING AND HAD NOTHING LEFT TO SAY.
     *
     * <p>Its own type because it is neither of the two things it would otherwise be mistaken for. It
     * is not a transport failure, so retrying the identical request costs another full budget to
     * arrive at the identical wall. And it is emphatically not SILENCE: {@link Insisting} exists to
     * re-ask a model that declined to answer, and a truncation handed to it is re-asked as though
     * the model had nothing to say, when what happened is that it was cut off mid-thought.
     *
     * <p>Measured on this project's own endpoint, one prompt, no reasoning budget, a 3,000-token
     * cap: 9,488 characters of reasoning, ZERO characters of content, {@code finish_reason: length}.
     * Before this the runtime returned that empty string as the agent's answer, and a consumer that
     * writes what the agent returned wrote nothing over a file that had something in it.
     */
    public static final class Truncated extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public Truncated(String because) {
            super(because);
        }
    }


    private final StreamingChatModel streaming;
    private final Trace trace;
    private final Watch watch;

    Streamed(StreamingChatModel streaming, Trace trace) {
        this(streaming, trace, Watch.fromEnv());
    }

    Streamed(StreamingChatModel streaming, Trace trace, Watch watch) {
        this.streaming = streaming;
        this.trace = trace;
        this.watch = watch;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return doChat(request);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        // One slot, because there is exactly one terminal event per call and the producer must
        // never block on a consumer that has already given up.
        BlockingQueue<Object> done = new ArrayBlockingQueue<>(1);
        AtomicLong lastToken = new AtomicLong(System.currentTimeMillis());
        // WHAT THE STREAM HAD SAID BEFORE IT STOPPED. These arrived and were thrown away — the
        // handler took the text and used it only to stamp a clock. So when a stall or a ceiling
        // fired, the record showed a throw and nothing else, and the tokens that WERE produced
        // were the whole diagnosis: a reasoning loop repeating itself, a tool call half-emitted,
        // a model answering a different question. Truncated argues hardest for keeping them,
        // because its whole definition is that the answer is blank — the reasoning that spent the
        // budget is the only thing there is to look at.
        StringBuilder soFar = new StringBuilder();

        streaming.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                lastToken.set(System.currentTimeMillis());
                synchronized (soFar) {
                    soFar.append(partial);
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partial) {
                // Counts as liveness even though this server's reasoning never arrives here: the
                // deltas are read at the transport, where the field name does not have to match.
                lastToken.set(System.currentTimeMillis());
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.offer(response);
            }

            @Override
            public void onError(Throwable error) {
                done.offer(error);
            }
        });

        long deadline = System.currentTimeMillis() + watch.ceiling().toMillis();
        while (true) {
            Object outcome;
            try {
                outcome = done.poll(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while streaming", e);
            }
            if (outcome instanceof ChatResponse response) {
                // AN EMPTY ANSWER THAT RAN OUT OF ROOM IS NOT AN ANSWER. The finish reason has been
                // on the response all along; nothing looked at it. Content that is blank because the
                // budget was spent thinking is a truncation, and saying so is the difference between
                // a run that stops and a run that quietly writes an empty string over real work.
                String said = response.aiMessage() == null ? null : response.aiMessage().text();
                if ((said == null || said.isBlank())
                        && response.finishReason() == FinishReason.LENGTH) {
                    keep(soFar, "the budget went on reasoning and the answer came back blank");
                    throw new Truncated("the model reached the token limit before writing an answer"
                            + "; all of it went on reasoning. Raise the completion budget or lower "
                            + "the reasoning one (RATCHET_THINKING_TOKENS), or turn thinking off.");
                }
                return response;
            }
            if (outcome instanceof Throwable error) {
                throw error instanceof RuntimeException r ? r
                        : new IllegalStateException("stream failed: " + error.getMessage(), error);
            }
            long quiet = System.currentTimeMillis() - lastToken.get();
            if (quiet > watch.stall().toMillis()) {
                keep(soFar, "no token for " + (quiet / 60_000) + " minutes");
                throw new IllegalStateException("no token for " + (quiet / 60_000)
                        + " minutes: the connection is not producing");
            }
            if (System.currentTimeMillis() > deadline) {
                keep(soFar, "still streaming after " + watch.ceiling().toHours() + "h");
                throw new GaveUp("still streaming after " + watch.ceiling().toHours()
                        + "h; giving the lane back");
            }
        }
    }

    @Override
    public List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
        return streaming.listeners();
    }

    @Override
    public dev.langchain4j.model.chat.request.ChatRequestParameters defaultRequestParameters() {
        return streaming.defaultRequestParameters();
    }

    @Override
    public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
        return streaming.supportedCapabilities();
    }

    /**
     * PUT WHAT IT SAID INTO THE RECORD BEFORE THROWING.
     *
     * <p>This class has taken a {@link Trace} since it was written and never written to it — a grep
     * for {@code trace.} returned nothing at v0.11.1. A consumer noticed while trying to delete
     * their own copy in favour of this one, and kept theirs because of it: what a stalled
     * generation had already produced is usually the whole diagnosis, and losing it means the only
     * evidence of a three-hour lane is that it lasted three hours.
     *
     * <p>Recording must not break the call it is recording, which is the rule the rest of this
     * package keeps — a trace that cannot be written is not a reason to lose the failure underneath.
     */
    private void keep(StringBuilder soFar, String why) {
        if (trace == null) {
            return;
        }
        String said;
        synchronized (soFar) {
            said = soFar.toString();
        }
        try {
            trace.thought(why, said, "");
        } catch (RuntimeException recordingMustNotBreakTheRun) {
            // Deliberately swallowed. The throw that follows is the news.
        }
    }

    /**
     * The same first-non-blank chain {@link Model} uses, through {@link Env} so that blank-is-unset
     * is decided in one place rather than twice with a chance of disagreeing.
     */
    private static String setting(String name, String fallback) {
        String value = Env.get("RATCHET_" + name);
        if (value == null) {
            value = Env.get("OC_" + name);
        }
        if (value == null) {
            value = Env.get("BJV_" + name);
        }
        return value == null ? fallback : value;
    }
}
