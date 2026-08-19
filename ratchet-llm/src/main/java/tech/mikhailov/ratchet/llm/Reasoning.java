package tech.mikhailov.ratchet.llm;

import java.time.Duration;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.ratchet.record.Trace;

/**
 * CAPTURES THE REASONING THE CLIENT WOULD OTHERWISE DISCARD, at the transport.
 *
 * <p>The server puts its reasoning in a field called {@code reasoning}; the client reads a field
 * called {@code reasoning_content} and finds nothing. So {@code returnThinking} returns nothing,
 * the model's own listener never fires, and the reasoning is generated, paid for, and dropped:
 * eighty-four calls produced eighty-four blanks before this existed.
 *
 * <p>Reading the wire here works whatever either side calls the field, because it does not depend
 * on the client's mapping at all. It covers both transports: a whole body for a blocking call, and
 * the accumulated deltas for a streamed one. It is also the only layer that sees the whole answer: by the time
 * the runtime returns a String, the content is all that is left, and a call cut off mid-thought is
 * indistinguishable from one that declined to answer.
 *
 * <p>It touches nothing. The response is passed through exactly as received, and a failure to parse
 * is silent by design: a trace that cannot be written must never be the reason a run fails.
 *
 * <p>The field reading is {@link Json#read}, in the core artifact, because everything that ever
 * reads an argument a model wrote wants it and almost none of that is here.
 */
public final class Reasoning {

    private Reasoning() {
    }

    /** Wrap a client builder so every response is read for reasoning on its way past. */
    static HttpClientBuilder tee(HttpClientBuilder delegate, Trace trace) {
        return new HttpClientBuilder() {
            @Override
            public Duration connectTimeout() {
                return delegate.connectTimeout();
            }

            @Override
            public HttpClientBuilder connectTimeout(Duration t) {
                delegate.connectTimeout(t);
                return this;
            }

            @Override
            public Duration readTimeout() {
                return delegate.readTimeout();
            }

            @Override
            public HttpClientBuilder readTimeout(Duration t) {
                delegate.readTimeout(t);
                return this;
            }

            @Override
            public HttpClient build() {
                HttpClient inner = delegate.build();
                return new HttpClient() {
                    @Override
                    public SuccessfulHttpResponse execute(HttpRequest request) {
                        SuccessfulHttpResponse response = inner.execute(request);
                        record(trace, response.body());
                        return response;
                    }

                    @Override
                    public void execute(HttpRequest request, ServerSentEventParser parser,
                                        ServerSentEventListener listener) {
                        inner.execute(request, parser, new Accumulating(listener, trace));
                    }
                };
            }
        };
    }

    /**
     * Reads the reasoning out of the SSE deltas as they pass.
     *
     * <p>The same name disagreement as on the blocking path, and it survives the move to streaming:
     * the server puts its reasoning in {@code delta.reasoning} and the client reads
     * {@code reasoning_content}, so {@code onPartialThinking} never fires and the complete message
     * carries no thinking either. Every trace written after the streaming switch had zero thoughts
     * while every earlier one had them, which is what that mismatch looks like from the outside.
     *
     * <p>Accumulated per response and emitted once at the end, because a thought arrives one token
     * at a time and a trace of ten thousand one-token rows is not a record of anything.
     */
    private static final class Accumulating implements ServerSentEventListener {
        private final ServerSentEventListener delegate;
        private final Trace trace;
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private final java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        private String finish = "";
        /**
         * Fire ONCE per response.
         *
         * <p>Throwing out of the listener does not reliably cancel the stream: the client kept
         * delivering events, every later line re-tripped the already-satisfied counter, and one
         * genuine detection became 971 rows for a single run, 9,916 across one corpus against 776
         * normal finishes, with a median of 166 characters of thinking each. The detection was
         * right; repeating it was not.
         */
        private boolean detected;
        /** One thought row per response, whatever order onError, onClose and a detection arrive in. */
        private boolean written;

        Accumulating(ServerSentEventListener delegate, Trace trace) {
            this.delegate = delegate;
            this.trace = trace;
        }

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
            delegate.onOpen(response);
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            take(event);
            delegate.onEvent(event);
        }

        @Override
        public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            take(event);
            delegate.onEvent(event, context);
        }

        /**
         * Thrown when the reasoning has begun repeating itself.
         *
         * <p>Escapes the listener deliberately, so the client cancels its subscription and the
         * server stops generating. Swallowing it would save the trace and keep paying the bill.
         */
        static final class LoopDetected extends RuntimeException {
            LoopDetected(String line, int times) {
                super("reasoning repeated a line " + times + " times: "
                        + line.substring(0, Math.min(90, line.length())));
            }
        }

        /**
         * How many repeats of one substantial line count as a cycle rather than a habit.
         *
         * <p>Six, measured: catches 66 of 68 runaways in one corpus at a median 27% of the
         * reasoning, with 4 false positives in 824 healthy turns.
         */
        private static final int REPEATS = 6;
        private static final int SUBSTANTIAL = 60;

        private void watch(String added) {
            if (added.isEmpty()) {
                return;
            }
            pending.append(added);
            int nl;
            while ((nl = pending.indexOf("\n")) >= 0) {
                String line = pending.substring(0, nl);
                pending.delete(0, nl + 1);
                String norm = line.replaceAll("[\\s\\p{Punct}]+", " ").strip().toLowerCase();
                if (norm.length() < SUBSTANTIAL) {
                    continue;
                }
                int n = seen.merge(norm, 1, Integer::sum);
                if (n >= REPEATS && !detected) {
                    detected = true;
                    // Greedy decoding cannot leave a cycle it has entered: one experiment
                    // restarted 14 trapped generations with 2500 more tokens and 0 escaped. The
                    // budget is gone either way; the only choice is whether to keep paying for it.
                    finish = "loop";
                    flush();
                    throw new LoopDetected(line, n);
                }
            }
        }

        private void take(ServerSentEvent event) {
            try {
                String data = event == null ? null : event.data();
                if (data == null || data.isEmpty() || data.equals("[DONE]") || detected) {
                    return;
                }
                String reasoned = Json.read(data, "reasoning") + Json.read(data, "reasoning_content");
                thinking.append(reasoned);
                content.append(Json.read(data, "content"));
                watch(reasoned);
                String f = Json.read(data, "finish_reason");
                if (!f.isBlank()) {
                    finish = f;
                }
            } catch (LoopDetected stuck) {
                throw stuck;
            } catch (RuntimeException ignored) {
                // A chunk we cannot read is not a reason to break the stream carrying it.
            }
        }

        @Override
        public void onError(Throwable error) {
            flush();
            delegate.onError(error);
        }

        @Override
        public void onClose() {
            flush();
            delegate.onClose();
        }

        private void flush() {
            if (written) {
                return;
            }
            written = true;
            try {
                // Record whenever there is anything to say about how the answer ended, even with
                // no reasoning captured: only 75 of 182 empties were visible as `length` before,
                // and an empty with no row at all is an empty nobody can diagnose.
                if (thinking.length() == 0 && !finish.isBlank() && content.length() == 0) {
                    trace.thought(finish, "", "");
                    finish = "";
                    return;
                }
                if (thinking.length() > 0) {
                    trace.thought(finish, thinking.toString(), content.toString());
                    thinking.setLength(0);
                    content.setLength(0);
                }
            } catch (RuntimeException unrecordable) {
                // A trace that cannot be written must never be why a run fails.
            }
        }
    }

    static void record(Trace trace, String body) {
        try {
            if (body == null || body.isEmpty()) {
                return;
            }
            String thinking = Json.read(body, "reasoning");
            if (thinking.isBlank()) {
                thinking = Json.read(body, "reasoning_content");
            }
            if (thinking.isBlank()) {
                return;
            }
            trace.thought(Json.read(body, "finish_reason"), thinking, Json.read(body, "content"));
        } catch (RuntimeException unparseable) {
            // A body we cannot read is not a reason to fail a run.
        }
    }
}
