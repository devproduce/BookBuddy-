package com.bookbuddy.conversation;

import com.bookbuddy.config.BookBuddyProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Loads versioned prompt templates from {@code classpath:prompt/<id>.txt}. */
@Component
public class PromptLoader {

    public static final String DEFAULT_CHAT_PROMPT_ID = "chat.system.v1";

    private final BookBuddyProperties properties;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptLoader(BookBuddyProperties properties) {
        this.properties = properties;
    }

    /** Version id of the active chat system prompt, stored on every assistant message. */
    public String chatPromptId() {
        BookBuddyProperties.Prompt p = properties.prompt();
        return p == null || p.chatSystemId() == null || p.chatSystemId().isBlank()
                ? DEFAULT_CHAT_PROMPT_ID
                : p.chatSystemId();
    }

    public String chatSystemPrompt() {
        return load(chatPromptId());
    }

    public String load(String id) {
        return cache.computeIfAbsent(id, this::read);
    }

    private String read(String id) {
        if (!id.matches("[A-Za-z0-9._-]+")) {
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