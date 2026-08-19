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
final class Streamed implements ChatModel {

    /**
     * How long a silent connection may stay silent.
     *
     * <p>Generous, because the first token can be a long way behind the request on a shared GPU
     * that is prefilling a large context. It bounds a dead socket, not a slow one.
     */
    private static final Duration STALL = Duration.ofMinutes(
            Integer.parseInt(setting("STALL_MINUTES", "20")));

    /** A hard ceiling that exists only so a wedged lane cannot hold a slot forever. */
    private static final Duration CEILING = Duration.ofHours(
            Integer.parseInt(setting("CEILING_HOURS", "3")));

    private final StreamingChatModel streaming;
    private final Trace trace;

    Streamed(StreamingChatModel streaming, Trace trace) {
        this.streaming = streaming;
        this.trace = trace;
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

        streaming.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                lastToken.set(System.currentTimeMillis());
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

        long deadline = System.currentTimeMillis() + CEILING.toMillis();
        while (true) {
            Object outcome;
            try {
                outcome = done.poll(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while streaming", e);
            }
            if (outcome instanceof ChatResponse response) {
                return response;
            }
            if (outcome instanceof Throwable error) {
                throw error instanceof RuntimeException r ? r
                        : new IllegalStateException("stream failed: " + error.getMessage(), error);
            }
            long quiet = System.currentTimeMillis() - lastToken.get();
            if (quiet > STALL.toMillis()) {
                throw new IllegalStateException("no token for " + (quiet / 60_000)
                        + " minutes: the connection is not producing");
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("still streaming after " + CEILING.toHours()
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
