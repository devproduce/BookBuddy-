package com.bookbuddy.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.config.BookBuddyProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Exercises {@link GroqLlmProvider#streamChat} against a tiny in-process SSE server. */
class GroqStreamingTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GroqLlmProvider start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
        BookBuddyProperties.Groq cfg = new BookBuddyProperties.Groq(
                "test-key", "http://127.0.0.1:" + server.getAddress().getPort(), List.of("m-allowed"), "m-default");
        return new GroqLlmProvider(cfg, WebClient.builder());
    }

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String delta(String content) {
        return "{\"choices\":[{\"delta\":{\"content\":" + "\"" + content + "\"" + "}}]}";
    }

    @Test
    void streamsTokensInOrderAndReadsUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        GroqLlmProvider llm = start(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(event("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}").getBytes(StandardCharsets.UTF_8));
                out.write(event(delta("Hello")).getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write(event(delta(", ")).getBytes(StandardCharsets.UTF_8));
                out.write(event(delta("world")).getBytes(StandardCharsets.UTF_8));
                out.write(event("{\"choices\":[{\"delta\":{}}],\"x_groq\":{\"usage\":{\"prompt_tokens\":11,"
                        + "\"completion_tokens\":3,\"total_tokens\":14}}}").getBytes(StandardCharsets.UTF_8));
                out.write(event("[DONE]").getBytes(StandardCharsets.UTF_8));
            }
        });

        List<String> tokens = new ArrayList<>();
        StreamResult r = llm.streamChat(
                List.of(ChatMessage.system("be kind"), ChatMessage.user("hi")),
                new ChatOptions("m-allowed", 0.1, 50), tokens::add, new CancelToken());

        assertEquals(List.of("Hello", ", ", "world"), tokens);
        assertEquals("Hello, world", r.text());
        assertFalse(r.cancelled());
        assertEquals("m-allowed", r.modelId());
        assertNotNull(r.usage());
        assertEquals(14, r.usage().totalTokens());
        assertFalse(r.usage().estimated());
        assertEquals("Bearer test-key", auth.get());
        assertTrue(requestBody.get().contains("\"stream\":true"), requestBody.get());
        assertTrue(requestBody.get().contains("\"model\":\"m-allowed\""));
        assertTrue(requestBody.get().contains("be kind"));
    }

    @Test
    void unknownModelFallsBackToTheDefault() throws Exception {
        GroqLlmProvider llm = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(event(delta("ok")).getBytes(StandardCharsets.UTF_8));
                out.write(event("[DONE]").getBytes(StandardCharsets.UTF_8));
            }
        });
        StreamResult r = llm.streamChat(List.of(ChatMessage.user("hi")), new ChatOptions("evil-model", 0.1, 50),
                t -> {}, new CancelToken());
        assertEquals("m-default", r.modelId());
    }

    @Test
    void cancellingStopsTheStreamEvenWhileTheServerKeepsTalking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GroqLlmProvider llm = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(event(delta("first")).getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(20, TimeUnit.SECONDS); // a slow model: nothing else arrives
                out.write(event(delta("never")).getBytes(StandardCharsets.UTF_8));
            } catch (IOException | InterruptedException ignored) {
                // client went away: expected
            }
        });

        CancelToken cancel = new CancelToken();
        List<String> tokens = new ArrayList<>();
        long started = System.nanoTime();
        StreamResult r = llm.streamChat(List.of(ChatMessage.user("hi")), null, t -> {
            tokens.add(t);
            cancel.cancel();
        }, cancel);
        release.countDown();

        assertTrue(r.cancelled());
        assertEquals(List.of("first"), tokens);
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10, "must not wait for the server");
    }

    @Test
    void httpErrorsBecomeLlmException() throws Exception {
        GroqLlmProvider llm = start(exchange -> {
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        LlmException ex = assertThrows(LlmException.class,
                () -> llm.streamChat(List.of(ChatMessage.user("hi")), null, t -> {}, new CancelToken()));
        assertTrue(ex.getMessage().contains("429"), ex.getMessage());
    }

    @Test
    void defaultProviderImplementationDeliversTheCompletionAsOneToken() {
        LlmProvider simple = new LlmProvider() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public String complete(String systemPrompt, String userPrompt) {
                return "sys=" + systemPrompt + "|user=" + userPrompt;
            }
        };
        List<String> tokens = new ArrayList<>();
        StreamResult r = simple.streamChat(List.of(ChatMessage.system("S"), ChatMessage.user("U")), null, tokens::add);
        assertEquals(1, tokens.size());
        assertTrue(r.text().startsWith("sys=S|user=user: U"), r.text());
    }

    @Test
    void cancelTokenRunsCallbacksOnceAndImmediatelyWhenAlreadyCancelled() {
        CancelToken token = new CancelToken();
        int[] calls = {0};
        token.onCancel(() -> calls[0]++);
        token.cancel();
        token.cancel();
        token.onCancel(() -> calls[0]++);
        assertEquals(2, calls[0]);
        assertTrue(token.isCancelled());
    }
}