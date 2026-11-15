package com.bookbuddy.conversation;

import com.bookbuddy.config.BookBuddyProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads versioned prompt templates from {@code classpath:prompt/<id>.txt}. The active ids come from
 * {@code bookbuddy.prompt.*}; any id can also be loaded directly, which is how evals compare versions.
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    public static final String DEFAULT_CHAT_PROMPT_ID = BookBuddyProperties.Prompt.DEFAULT_CHAT_SYSTEM_ID;
    public static final String DEFAULT_QUERY_REWRITE_PROMPT_ID = BookBuddyProperties.Prompt.DEFAULT_QUERY_REWRITE_ID;

    private final BookBuddyProperties properties;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptLoader(BookBuddyProperties properties) {
        this.properties = properties;
    }

    /**
     * Version id of the active chat system prompt, stored on every assistant message. A configured id whose file
     * does not exist falls back to {@link #DEFAULT_CHAT_PROMPT_ID} so a typo in config cannot break chat.
     */
    public String chatPromptId() {
        BookBuddyProperties.Prompt p = properties.prompt();
        return resolve(p == null ? null : p.chatSystemId(), DEFAULT_CHAT_PROMPT_ID);
    }

    public String chatSystemPrompt() {
        return load(chatPromptId());
    }

    /** Id of the active query-rewrite prompt. */
    public String queryRewritePromptId() {
        BookBuddyProperties.Prompt p = properties.prompt();
        return resolve(p == null ? null : p.queryRewriteId(), DEFAULT_QUERY_REWRITE_PROMPT_ID);
    }

    public String queryRewritePrompt() {
        return load(queryRewritePromptId());
    }

    /** True when {@code classpath:prompt/<id>.txt} exists. */
    public boolean exists(String id) {
        return validId(id) && getClass().getClassLoader().getResource("prompt/" + id + ".txt") != null;
    }

    /** Loads a prompt by id (cached). @throws IllegalStateException when the file does not exist */
    public String load(String id) {
        return cache.computeIfAbsent(id, this::read);
    }

    private String resolve(String configured, String fallback) {
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        String id = configured.strip();
        if (exists(id)) {
            return id;
        }
        log.warn("Prompt '{}' not found; using '{}'", id, fallback);
        return fallback;
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]+");
    }

    private String read(String id) {
        if (!validId(id)) {
            throw new IllegalArgumentException("Invalid prompt id");
        }
        String path = "prompt/" + id + ".txt";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Prompt resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
