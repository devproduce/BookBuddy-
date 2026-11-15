package com.bookbuddy.conversation;

import com.bookbuddy.ai.CancelToken;
import com.bookbuddy.config.AsyncConfig;
import com.bookbuddy.conversation.ConversationService.Turn;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Bridges a chat turn to Server-Sent Events.
 *
 * <pre>
 * event: meta       data: {"messageId","userMessageId","conversationId"}
 * event: token      data: {"text"}                         (repeated)
 * event: citations  data: {"citations":[...]}
 * event: memory     data: {"memoryIds":[...]}               (memories that shaped the reply, when any)
 * event: safety     data: {"level","resources":[{"name","phone","personal"}]}
 * event: done       data: {"messageId","status","content","usage","modelId"}
 * event: error      data: {"code","message","retryable","messageId"}
 * : keepalive                                              (comment every 15 s)
 * </pre>
 *
 * When the client disconnects, the turn's {@link CancelToken} fires, which stops the upstream LLM request.
 */
@Component
public class ChatStreamer {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamer.class);

    static final long HEARTBEAT_SECONDS = 15;
    private static final long STREAM_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    private final ConversationService service;
    private final Executor executor;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chat-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public ChatStreamer(ConversationService service, @Qualifier(AsyncConfig.CHAT_EXECUTOR) Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
    }

    public SseEmitter stream(Turn turn) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        CancelToken cancel = new CancelToken();
        SseSink sink = new SseSink(emitter, cancel);

        emitter.onCompletion(cancel::cancel);
        emitter.onTimeout(() -> {
            cancel.cancel();
            emitter.complete();
        });
        emitter.onError(e -> cancel.cancel());

        sink.comment("open"); // flushes the response headers right away
        ScheduledFuture<?> heartbeat =
                heartbeats.scheduleAtFixedRate(() -> sink.comment("keepalive"), HEARTBEAT_SECONDS,
                        HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        try {
            executor.execute(() -> {
                try {
                    service.runTurn(turn, sink, cancel);
                } catch (RuntimeException e) {
                    log.warn("Chat stream crashed: {}", e.getClass().getSimpleName());
                    sink.error(new ChatEvents.Error(
                            "GENERATION_FAILED", "I couldn't finish that reply. Please try again.", true, null));
                } finally {
                    heartbeat.cancel(false);
                    sink.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            heartbeat.cancel(false);
            sink.error(new ChatEvents.Error("BUSY", "Book Buddy is busy right now. Please try again in a moment.",
                    true, null));
            sink.complete();
        }
        return emitter;
    }

    /** Thread-safe {@link ChatEvents.Sink} writing SSE frames. A failed write means the client left: cancel. */
    static final class SseSink implements ChatEvents.Sink {

        private final SseEmitter emitter;
        private final CancelToken cancel;
        private final Object lock = new Object();
        private boolean closed;

        SseSink(SseEmitter emitter, CancelToken cancel) {
            this.emitter = emitter;
            this.cancel = cancel;
        }

        @Override
        public void meta(ChatEvents.Meta meta) {
            send("meta", meta);
        }

        @Override
        public void token(String text) {
            send("token", Map.of("text", text));
        }

        @Override
        public void citations(List<MessageCitation> citations) {
            send("citations", Map.of("citations", citations));
        }

        @Override
        public void safety(ChatEvents.Safety safety) {
            send("safety", safety);
        }

        @Override
        public void memory(List<String> memoryIds) {
            send("memory", Map.of("memoryIds", memoryIds));
        }

        @Override
        public void done(ChatEvents.Done done) {
            send("done", done);
        }

        @Override
        public void error(ChatEvents.Error error) {
            send("error", error);
        }

        void comment(String text) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().comment(text));
                } catch (IOException | IllegalStateException e) {
                    closed = true;
                    cancel.cancel();
                }
            }
        }

        void complete() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // already completed
                }
            }
        }

        private void send(String name, Object data) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    closed = true;
                    cancel.cancel();
                }
            }
        }
    }
}