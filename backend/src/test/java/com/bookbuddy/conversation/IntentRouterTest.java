package com.bookbuddy.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bookbuddy.conversation.IntentRouter.Intent;
import org.junit.jupiter.api.Test;

class IntentRouterTest {

    private final IntentRouter router = new IntentRouter();

    @Test
    void greetingsAndThanksAreChitchat() {
        for (String s : new String[] {"hi", "Hello!", "hey there", "thanks", "thank you!", "good morning", "namaste",
            "\u0927\u0928\u094D\u092F\u0935\u093E\u0926", "ok"}) {
            assertEquals(Intent.CHITCHAT, router.route(s, false), s);
        }
    }

    @Test
    void ventingIsPersonalReflectionButStillMayRetrieve() {
        // Soft retrieval: passages are offered; the system prompt forbids forcing books into pure venting.
        for (String s : new String[] {
            "I feel so tired and stressed today",
            "I just need to vent about my boss",
            "I'm lonely tonight",
            "mujhe bahut akela lag raha hai",
            "\u092E\u0941\u091D\u0947 \u092C\u0939\u0941\u0924 \u0909\u0926\u093E\u0938 \u0932\u0917 \u0930\u0939\u093E \u0939\u0948"}) {
            Intent intent = router.route(s, false);
            assertEquals(Intent.PERSONAL_REFLECTION, intent, s);
            assertTrue(intent.needsBooks());
        }
    }

    @Test
    void bookCuesRouteToBookQuestion() {
        for (String s : new String[] {
            "Who is the narrator of this novel?",
            "Summarize chapter 3",
            "What is the theme of the story",
            "is kitab ka ending kya hai",
            "\u0907\u0938 \u0915\u0939\u093E\u0928\u0940 \u0915\u093E \u0915\u093F\u0930\u0926\u093E\u0930 \u0915\u094C\u0928 \u0939\u0948"}) {
            Intent intent = router.route(s, false);
            assertEquals(Intent.BOOK_QUESTION, intent, s);
            assertTrue(intent.needsBooks());
        }
    }

    @Test
    void bookAndFeelingTogetherIsMixed() {
        assertEquals(Intent.MIXED, router.route("I feel so sad after the ending of this book", false));
    }

    @Test
    void asciiCuesNeedWordBoundaries() {
        // "page" must not fire inside other words; without personal/book cues this is a book-default message
        Intent intent = router.route("we are on a different stage of life honestly", false);
        assertEquals(Intent.BOOK_QUESTION, intent);
        assertFalse(intent.name().isBlank());
    }

    @Test
    void unclearSubstantiveMessagesDefaultToBookQuestion() {
        assertEquals(Intent.BOOK_QUESTION, router.route("what happens next?", false));
        assertEquals(Intent.BOOK_QUESTION, router.route("tell me what happens next", true));
        assertEquals(Intent.BOOK_QUESTION, router.route("tell me what happens next", false));
        assertEquals(Intent.BOOK_QUESTION, router.route("explain the idea of detachment", false));
        assertEquals(Intent.CHITCHAT, router.route("", false));
        assertEquals(Intent.CHITCHAT, router.route("ok", false));
    }

    @Test
    void onlyChitchatSkipsBookRetrieval() {
        assertFalse(Intent.CHITCHAT.needsBooks());
        assertTrue(Intent.BOOK_QUESTION.needsBooks());
        assertTrue(Intent.MIXED.needsBooks());
        assertTrue(Intent.PERSONAL_REFLECTION.needsBooks());
    }
}