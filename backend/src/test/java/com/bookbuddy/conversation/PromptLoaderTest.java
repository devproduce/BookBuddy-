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
    void defaultsToV2WhenNothingIsConfigured() {
        PromptLoader p = new PromptLoader(
                new BookBuddyProperties(null, null, null, null, null, null, null, null, null));
        assertEquals("chat.system.v2", p.chatPromptId());
        assertEquals("query-rewrite.v1", p.queryRewritePromptId());
    }

    @Test
    void configuredIdSwitchesTheActiveChatPrompt() {
        PromptLoader v1 = loader("chat.system.v1", null);
        PromptLoader v2 = loader("chat.system.v2", null);

        assertEquals("chat.system.v1", v1.chatPromptId());
        assertEquals("chat.system.v2", v2.chatPromptId());
        assertNotEquals(v1.chatSystemPrompt(), v2.chatSystemPrompt());
        // v2 is the one that knows about Hinglish and brevity; v1 predates them
        assertTrue(v2.chatSystemPrompt().contains("Hinglish"));
        assertTrue(v2.chatSystemPrompt().contains("Be brief"));
        assertFalse(v1.chatSystemPrompt().contains("Be brief"));
    }

    @Test
    void bothVersionsKeepTheGroundingRules() {
        for (String id : new String[] {"chat.system.v1", "chat.system.v2"}) {
            String text = loader(id, null).chatSystemPrompt();
            assertTrue(text.contains("<<<PASSAGE n>>>"), id);
            assertTrue(text.contains("[1]"), id);
            assertTrue(text.contains("word for word"), id);
        }
    }

    @Test
    void loadsAnyVersionByIdIndependentOfConfiguration() {
        PromptLoader p = loader("chat.system.v2", null);
        assertEquals(loader("chat.system.v1", null).chatSystemPrompt(), p.load("chat.system.v1"));
        assertTrue(p.exists("chat.system.v1"));
        assertFalse(p.exists("chat.system.v99"));
    }

    @Test
    void unknownConfiguredIdFallsBackToTheDefaultInsteadOfBreakingChat() {
        PromptLoader p = loader("chat.system.v99", "nope");
        assertEquals("chat.system.v2", p.chatPromptId());
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
