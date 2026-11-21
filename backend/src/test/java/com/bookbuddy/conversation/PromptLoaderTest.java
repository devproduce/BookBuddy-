package com.bookbuddy.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.config.BookBuddyProperties;
import org.junit.jupiter.api.Test;

class PromptLoaderTest {

    private static PromptLoader loader(String chatId, String rewriteId) {
        return new PromptLoader(new BookBuddyProperties(
                null, null, null, null, null, null, null, null, new BookBuddyProperties.Prompt(chatId, rewriteId)));
    }

    @Test
    void defaultsToV3WhenNothingIsConfigured() {
        PromptLoader p = new PromptLoader(
                new BookBuddyProperties(null, null, null, null, null, null, null, null, null));
        assertEquals("chat.system.v3", p.chatPromptId());
        assertEquals("query-rewrite.v1", p.queryRewritePromptId());
    }

    @Test
    void configuredIdSwitchesTheActiveChatPrompt() {
        PromptLoader v1 = loader("chat.system.v1", null);
        PromptLoader v3 = loader("chat.system.v3", null);

        assertEquals("chat.system.v1", v1.chatPromptId());
        assertEquals("chat.system.v3", v3.chatPromptId());
        assertNotEquals(v1.chatSystemPrompt(), v3.chatSystemPrompt());
        // v3 (and v2) know about Hinglish and friend voice; v1 predates them
        assertTrue(v3.chatSystemPrompt().contains("Hinglish"));
        assertTrue(v3.chatSystemPrompt().contains("close friend"));
        assertTrue(v3.chatSystemPrompt().contains("Hard bans"));
        assertTrue(v3.chatSystemPrompt().contains("How does that feel?"));
        assertFalse(v1.chatSystemPrompt().contains("Hard bans"));
    }

    @Test
    void allVersionsKeepTheGroundingRules() {
        for (String id : new String[] {"chat.system.v1", "chat.system.v2", "chat.system.v3"}) {
            String text = loader(id, null).chatSystemPrompt();
            assertTrue(text.contains("<<<PASSAGE n>>>"), id);
            assertTrue(text.contains("[1]"), id);
            assertTrue(text.contains("word for word"), id);
        }
    }

    @Test
    void loadsAnyVersionByIdIndependentOfConfiguration() {
        PromptLoader p = loader("chat.system.v3", null);
        assertEquals(loader("chat.system.v1", null).chatSystemPrompt(), p.load("chat.system.v1"));
        assertTrue(p.exists("chat.system.v1"));
        assertTrue(p.exists("chat.system.v2"));
        assertFalse(p.exists("chat.system.v99"));
    }

    @Test
    void unknownConfiguredIdFallsBackToTheDefaultInsteadOfBreakingChat() {
        PromptLoader p = loader("chat.system.v99", "nope");
        assertEquals("chat.system.v3", p.chatPromptId());
        assertEquals("query-rewrite.v1", p.queryRewritePromptId());
        assertFalse(p.chatSystemPrompt().isBlank());
    }

    @Test
    void loadingAMissingOrMalformedIdFails() {
        PromptLoader p = loader(null, null);
        assertThrows(IllegalStateException.class, () -> p.load("chat.system.v99"));
        assertThrows(IllegalArgumentException.class, () -> p.load("../application"));
        assertFalse(p.exists("../application"));
    }

    @Test
    void queryRewritePromptIsLoadable() {
        String text = loader(null, null).queryRewritePrompt();
        assertTrue(text.contains("standalone"));
    }
}
