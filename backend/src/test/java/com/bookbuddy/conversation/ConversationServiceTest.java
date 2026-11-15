package com.bookbuddy.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bookbuddy.ai.CancelToken;
import com.bookbuddy.ai.ChatMessage;
import com.bookbuddy.ai.LlmException;
import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.ai.StreamResult;
import com.bookbuddy.ai.TokenUsage;
import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.book.BookDocument;
import com.bookbuddy.book.BookRepository;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.config.BookBuddyProperties;
import com.bookbuddy.conversation.ChatEvents.Done;
import com.bookbuddy.conversation.ChatEvents.Meta;
import com.bookbuddy.conversation.ChatEvents.Safety;
import com.bookbuddy.conversation.ConversationDocument.ScopeMode;
import com.bookbuddy.conversation.ConversationDtos.BookScopeInput;
import com.bookbuddy.conversation.ConversationDtos.ConversationResponse;
import com.bookbuddy.conversation.ConversationDtos.CreateConversationRequest;
import com.bookbuddy.conversation.ConversationDtos.CursorPage;
import com.bookbuddy.conversation.ConversationDtos.MessageResponse;
import com.bookbuddy.conversation.ConversationDtos.SendMessageRequest;
import com.bookbuddy.conversation.ConversationDtos.SendMessageResponse;
import com.bookbuddy.conversation.MessageDocument.Role;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.document.SectionType;
import com.bookbuddy.document.TextCleaningService;
import com.bookbuddy.embedding.HashEmbeddingProvider;
import com.bookbuddy.memory.MemoryContext;
import com.bookbuddy.memory.MemoryExtractionService;
import com.bookbuddy.memory.MemoryRetrievalService;
import com.bookbuddy.quota.LlmBudgetService;
import com.bookbuddy.rag.QuoteVerifier;
import com.bookbuddy.rag.RagService;
import com.bookbuddy.safety.CrisisResponseService;
import com.bookbuddy.safety.RiskClassifier;
import com.bookbuddy.safety.RiskLevel;
import com.bookbuddy.safety.SafetyEventLogger;
import com.bookbuddy.vector.BookChunkDocument;
import com.bookbuddy.vector.HybridSearchService;
import com.bookbuddy.vector.HybridSearchService.HybridHit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

class ConversationServiceTest {

    private static final String USER = "u1";
    private static final String CONV = "c1";

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final BookRepository books = mock(BookRepository.class);
    private final HybridSearchService search = mock(HybridSearchService.class);
    private final LlmProvider llm = mock(LlmProvider.class);
    private final RollingSummaryService summaries = mock(RollingSummaryService.class);
    private final SafetyEventLogger safetyLogger = mock(SafetyEventLogger.class);
    private final MemoryRetrievalService memoryRetrieval = mock(MemoryRetrievalService.class);
    private final MemoryExtractionService memoryExtraction = mock(MemoryExtractionService.class);
    private final LlmBudgetService budget = mock(LlmBudgetService.class);

    private final List<MessageDocument> store = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger clock = new AtomicInteger();

    private ConversationService service;
    private ConversationDocument conversation;
    private UserDocument user;
    private BookBuddyProperties props;

    @BeforeEach
    void setUp() {
        props = new BookBuddyProperties(
                null, null, null, null, null, null,
                new BookBuddyProperties.Safety(List.of(new BookBuddyProperties.CrisisResource("Tele-MANAS", "14416"))),
                null, null);
        TextCleaningService cleaning = new TextCleaningService();
        QuoteVerifier verifier = new QuoteVerifier(cleaning);
        RagService rag = new RagService(books, new HashEmbeddingProvider(), search, llm, verifier, cleaning, props);

        service = new ConversationService(
                conversations, messages, users, books, rag, llm,
                new RiskClassifier(llm), new CrisisResponseService(props), safetyLogger, verifier,
                new IntentRouter(), new PromptLoader(props), summaries, memoryRetrieval, memoryExtraction, budget);
        service.setWordDelayMs(0);
        when(memoryRetrieval.retrieve(anyString(), anyString())).thenReturn(MemoryContext.EMPTY);

        user = new UserDocument();
        user.setId(USER);
        user.setConsent(new UserDocument.Consent("v1", Instant.now()));
        user.setDefaultLanguage("en");
        when(users.findById(USER)).thenReturn(Optional.of(user));

        conversation = new ConversationDocument();
        conversation.setId(CONV);
        conversation.setUserId(USER);
        conversation.setTitle(ConversationService.DEFAULT_TITLE);
        conversation.setBookScope(ConversationDocument.BookScope.all());
        when(conversations.findByIdAndUserId(CONV, USER)).thenReturn(Optional.of(conversation));
        when(conversations.save(any(ConversationDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        BookDocument book = new BookDocument();
        book.setId("b1");
        book.setUserId(USER);
        book.setTitle("The Lighthouse");
        when(books.findByUserId(USER)).thenReturn(List.of(book));
        when(books.findByIdAndUserId("b1", USER)).thenReturn(Optional.of(book));
        when(books.findByIdAndUserId("nope", USER)).thenReturn(Optional.empty());

        // in-memory message store
        when(messages.save(any(MessageDocument.class))).thenAnswer(inv -> {
            MessageDocument m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(String.format("m%03d", ids.incrementAndGet()));
            }
            store.removeIf(x -> x.getId().equals(m.getId()));
            store.add(m);
            return m;
        });
        when(messages.countByConversationId(anyString()))
                .thenAnswer(inv -> (long) store.stream().filter(m -> m.getConversationId().equals(inv.getArgument(0))).count());
        when(messages.findByConversationIdAndUserIdOrderByCreatedAtDescIdDesc(anyString(), anyString(), any(Limit.class)))
                .thenAnswer(inv -> store.stream()
                        .sorted(Comparator.comparing(MessageDocument::getCreatedAt)
                                .thenComparing(MessageDocument::getId)
                                .reversed())
                        .limit(((Limit) inv.getArgument(2)).max())
                        .toList());
        when(messages.findByIdAndConversationIdAndUserId(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> store.stream().filter(m -> m.getId().equals(inv.getArgument(0))).findFirst());
        doAnswer(inv -> store.removeIf(m -> m.getId().equals(inv.getArgument(0)))).when(messages).deleteById(anyString());

        when(llm.isConfigured()).thenReturn(false);
    }

    // ---------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------

    private static final class Recorder implements ChatEvents.Sink {
        final List<String> events = new ArrayList<>();
        final StringBuilder tokens = new StringBuilder();
        Meta meta;
        Safety safety;
        List<MessageCitation> citations;
        Done done;
        ChatEvents.Error error;

        @Override
        public void meta(Meta m) {
            meta = m;
            events.add("meta");
        }

        @Override
        public void token(String text) {
            tokens.append(text);
            if (!events.contains("token")) {
                events.add("token");
            }
        }

        @Override
        public void citations(List<MessageCitation> c) {
            citations = c;
            events.add("citations");
        }

        @Override
        public void safety(Safety s) {
            safety = s;
            events.add("safety");
        }

        List<String> memoryIds;

        @Override
        public void memory(List<String> ids) {
            memoryIds = ids;
            events.add("memory");
        }

        @Override
        public void done(Done d) {
            done = d;
            events.add("done");
        }

        @Override
        public void error(ChatEvents.Error e) {
            error = e;
            events.add("error");
        }
    }

    private MessageDocument seed(Role role, String content, Status status) {
        MessageDocument m = new MessageDocument();
        m.setConversationId(CONV);
        m.setUserId(USER);
        m.setRole(role);
        m.setContent(content);
        m.setStatus(status);
        m.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusMillis(clock.incrementAndGet() * 10L));
        return messages.save(m);
    }

    private static BookChunkDocument chunk(String id, String text, int page) {
        BookChunkDocument c = new BookChunkDocument();
        c.setId(id);
        c.setBookId("b1");
        c.setContent(text);
        c.setPageStart(page);
        c.setPageEnd(page);
        c.setSectionType(SectionType.BODY);
        return c;
    }

    private void retrieves(BookChunkDocument... chunks) {
        List<HybridHit> hits = java.util.Arrays.stream(chunks).map(c -> new HybridHit(c, 0.03, 0.5, true)).toList();
        when(search.search(anyString(), anyString(), any(float[].class), anyCollection(), anyInt(), anyDouble()))
                .thenReturn(hits);
    }

    private static SendMessageRequest say(String text) {
        return new SendMessageRequest(text, null, null);
    }

    private Recorder run(String text) {
        Recorder rec = new Recorder();
        ConversationService.Turn turn = service.startSend(USER, CONV, say(text));
        service.runTurn(turn, rec, new CancelToken());
        return rec;
    }

    private void llmStreams(String... tokens) {
        when(llm.isConfigured()).thenReturn(true);
        doAnswer(inv -> {
            Consumer<String> sink = inv.getArgument(2);
            StringBuilder all = new StringBuilder();
            for (String t : tokens) {
                sink.accept(t);
                all.append(t);
            }
            return new StreamResult(all.toString(), TokenUsage.of(120, 30, false), "test-model", false);
        }).when(llm).streamChat(any(), any(), any(), any());
    }

    private List<MessageDocument> stored(Role role) {
        return store.stream().filter(m -> m.getRole() == role).toList();
    }

    // ---------------------------------------------------------------------------------------------------------
    // consent + persistence order
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void chatIsBlockedUntilConsentIsAccepted() {
        user.setConsent(null);
        ApiException ex = assertThrows(ApiException.class, () -> service.startSend(USER, CONV, say("hello")));
        assertEquals(403, ex.getStatus().value());
        assertEquals("CONSENT_REQUIRED", ex.getCode());
        assertTrue(store.isEmpty(), "nothing may be stored without consent");
    }

    @Test
    void userMessageIsPersistedBeforeSafetyRetrievalOrGeneration() {
        retrieves(chunk("c1", "The keeper lived alone for forty years.", 3));
        llmStreams("ok");
        when(search.search(anyString(), anyString(), any(float[].class), anyCollection(), anyInt(), anyDouble()))
                .thenAnswer(inv -> {
                    assertEquals(1, stored(Role.USER).size(), "user message must already be saved when retrieval runs");
                    return List.of();
                });

        run("What did the keeper do in the book?");

        assertEquals(1, stored(Role.USER).size());
        assertEquals("What did the keeper do in the book?", stored(Role.USER).get(0).getContent());
    }

    @Test
    void firstMessageNamesTheConversation() {
        run("hello");
        assertEquals("hello", conversation.getTitle());
        assertEquals(2, conversation.getMessageCount());
    }

    // ---------------------------------------------------------------------------------------------------------
    // ACUTE
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void acuteMessageGetsCrisisResponseAndSkipsRagLlmAndPersonalisation() {
        user.setCrisisContacts(List.of(new UserDocument.CrisisContact("Asha", "+91 99999 11111")));
        when(llm.isConfigured()).thenReturn(true);

        Recorder rec = run("I want to kill myself");

        verifyNoInteractions(search);
        verify(llm, never()).streamChat(any(), any(), any(), any());
        verify(llm, never()).complete(anyString(), anyString());
        verify(safetyLogger).log(RiskLevel.ACUTE);

        assertEquals(List.of("meta", "safety", "token", "done"), rec.events);
        assertEquals(RiskLevel.ACUTE, rec.safety.level());
        assertEquals("Asha", rec.safety.resources().get(0).name());
        assertTrue(rec.safety.resources().get(0).personal());
        assertEquals("14416", rec.safety.resources().get(1).phone());

        MessageDocument reply = stored(Role.ASSISTANT).get(0);
        assertEquals(Status.COMPLETE, reply.getStatus());
        assertEquals(RiskLevel.ACUTE, reply.getSafetyLevel());
        assertEquals(ConversationService.CRISIS_PROMPT_VERSION, reply.getPromptVersion());
        assertEquals(CrisisResponseService.message("en"), reply.getContent());
        assertTrue(reply.getCitations().isEmpty());
        assertEquals(RiskLevel.ACUTE, stored(Role.USER).get(0).getSafetyLevel());
        assertEquals(reply.getId(), rec.done.messageId());
    }

    @Test
    void hindiAcuteMessageGetsHindiCrisisText() {
        Recorder rec = run("\u092E\u0948\u0902 \u0906\u0924\u094D\u092E\u0939\u0924\u094D\u092F\u093E \u0915\u0930\u0928\u093E \u091A\u093E\u0939\u0924\u093E \u0939\u0942\u0901");
        assertEquals(RiskLevel.ACUTE, rec.safety.level());
        assertEquals(CrisisResponseService.message("hi"), stored(Role.ASSISTANT).get(0).getContent());
    }

    @Test
    void elevatedMessageContinuesNormallyButShowsResourcesAndGentlePrompt() {
        llmStreams("I'm sorry you feel that way.");

        Recorder rec = run("I feel so hopeless lately");

        assertEquals(RiskLevel.ELEVATED, rec.safety.level());
        assertEquals(Status.COMPLETE, stored(Role.ASSISTANT).get(0).getStatus());
        ArgumentCaptor<List<ChatMessage>> prompt = promptCaptor();
        assertTrue(prompt.getValue().get(0).content().contains("Safety note"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // intent routing
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void chitchatAndVentingSkipBookRetrieval() {
        llmStreams("Hi! How are you?");

        run("hello");
        run("I feel so tired and stressed today, I just need to vent");

        verifyNoInteractions(search);
        verify(llm, org.mockito.Mockito.times(2)).streamChat(any(), any(), any(), any());
        assertEquals(2, stored(Role.ASSISTANT).size());
        assertTrue(stored(Role.ASSISTANT).stream().allMatch(m -> m.getCitations().isEmpty()));
    }

    @Test
    void scopeNoneNeverRetrievesEvenForBookQuestions() {
        conversation.setBookScope(new ConversationDocument.BookScope(ScopeMode.NONE, List.of()));
        llmStreams("From general knowledge...");

        run("Who is the main character of the book?");

        verifyNoInteractions(search);
        ArgumentCaptor<List<ChatMessage>> prompt = promptCaptor();
        assertTrue(prompt.getValue().get(0).content().contains("turned book lookup off"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // book questions + quote verification
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void bookQuestionStreamsAnswerWithCitationsAndStripsInventedQuotes() {
        retrieves(chunk("ch1", "The keeper lived alone for forty years.", 3));
        llmStreams("The keeper stayed \"for forty ", "years\" [1] and said ", "\"dragons guard the coast\".");

        Recorder rec = run("How long did the keeper live alone in the book?");

        assertEquals(List.of("meta", "token", "citations", "done"), rec.events);
        // tokens are streamed as the model produced them...
        assertTrue(rec.tokens.toString().contains("\"dragons guard the coast\""));
        // ...but the persisted / final text has been quote-verified
        MessageDocument reply = stored(Role.ASSISTANT).get(0);
        assertTrue(reply.getContent().contains("\"for forty years\""), reply.getContent());
        assertFalse(reply.getContent().contains("\"dragons guard the coast\""), reply.getContent());
        assertTrue(reply.getContent().contains("dragons guard the coast"));
        assertEquals(reply.getContent(), rec.done.content());

        assertEquals(1, reply.getCitations().size());
        MessageCitation cite = reply.getCitations().get(0);
        assertEquals(1, cite.index());
        assertEquals("The Lighthouse", cite.bookTitle());
        assertEquals(3, cite.pageStart());
        assertEquals("The keeper lived alone for forty years.", cite.excerpt());
        assertEquals("test-model", reply.getModelId());
        assertEquals("chat.system.v1", reply.getPromptVersion());
        assertEquals(150, reply.getTokenUsage().totalTokens());
        assertEquals(Status.COMPLETE, reply.getStatus());

        ArgumentCaptor<List<ChatMessage>> prompt = promptCaptor();
        List<ChatMessage> sent = prompt.getValue();
        assertEquals("system", sent.get(0).role());
        assertTrue(sent.get(0).content().contains("Book Buddy"));
        ChatMessage last = sent.get(sent.size() - 1);
        assertEquals("user", last.role());
        assertTrue(last.content().contains("<<<PASSAGE 1 | The Lighthouse | p. 3>>>"));
    }

    @Test
    void offlineModeStreamsAnExtractiveAnswerWordByWordWithVerbatimQuotes() {
        retrieves(chunk("ch1", "The keeper lived alone for forty years. Gulls circled.", 12));

        Recorder rec = run("How long did the keeper live alone in the book?");

        verify(llm, never()).streamChat(any(), any(), any(), any());
        assertTrue(rec.tokens.toString().contains("\u201CThe keeper lived alone for forty years.\u201D"), rec.tokens.toString());
        assertTrue(rec.tokens.toString().contains("[1]"));
        MessageDocument reply = stored(Role.ASSISTANT).get(0);
        assertEquals(ConversationService.OFFLINE_MODEL, reply.getModelId());
        assertEquals(1, reply.getCitations().size());
        assertEquals(reply.getContent(), rec.done.content());
    }

    @Test
    void bookQuestionWithNothingRelevantSaysSoWithoutCallingTheModel() {
        retrieves();
        when(llm.isConfigured()).thenReturn(true);

        Recorder rec = run("What colour was the lighthouse in the book?");

        verify(llm, never()).streamChat(any(), any(), any(), any());
        assertEquals(RagService.NO_ANSWER, stored(Role.ASSISTANT).get(0).getContent());
        assertNull(rec.citations);
    }

    @Test
    void conversationScopeRestrictsRetrievalToTheChosenBooks() {
        conversation.setBookScope(new ConversationDocument.BookScope(ScopeMode.ONE, List.of("b1")));
        retrieves();

        run("Who wrote the diary?");

        ArgumentCaptor<java.util.Collection<String>> scoped = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(search).search(eq(USER), anyString(), any(float[].class), scoped.capture(), anyInt(), anyDouble());
        assertEquals(List.of("b1"), List.copyOf(scoped.getValue()));
    }

    @Test
    void historyIsIncludedWithoutOldCitationMarkersOrFailedReplies() {
        seed(Role.USER, "Earlier question", Status.COMPLETE);
        seed(Role.ASSISTANT, "Earlier answer [1] with detail.", Status.COMPLETE);
        seed(Role.USER, "A question whose answer failed", Status.COMPLETE);
        seed(Role.ASSISTANT, "", Status.FAILED);
        llmStreams("fine");

        run("hello again");

        List<ChatMessage> sent = promptCaptor().getValue();
        List<String> contents = sent.stream().map(ChatMessage::content).toList();
        assertTrue(contents.contains("Earlier question"));
        assertTrue(contents.contains("Earlier answer with detail."), contents.toString());
        assertTrue(contents.stream().noneMatch(c -> c.isEmpty()));
        assertEquals("hello again", sent.get(sent.size() - 1).content());
    }

    @Test
    void summaryIsInjectedAndMessagesBeforeItAreSkipped() {
        MessageDocument old1 = seed(Role.USER, "Ancient question", Status.COMPLETE);
        seed(Role.ASSISTANT, "Ancient answer", Status.COMPLETE);
        conversation.setSummary("The reader loves lighthouse stories.");
        conversation.setLastSummarizedMessageId(stored(Role.ASSISTANT).get(0).getId());
        seed(Role.USER, "Recent question", Status.COMPLETE);
        seed(Role.ASSISTANT, "Recent answer", Status.COMPLETE);
        assertNotNull(old1.getId());
        llmStreams("fine");

        run("hello again");

        List<ChatMessage> sent = promptCaptor().getValue();
        assertTrue(sent.get(0).content().contains("The reader loves lighthouse stories."));
        List<String> contents = sent.stream().map(ChatMessage::content).toList();
        assertFalse(contents.contains("Ancient question"));
        assertTrue(contents.contains("Recent question"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // cancellation + failure
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void cancellingMidStreamSavesPartialTextAsInterruptedAndEmitsNoDone() {
        when(llm.isConfigured()).thenReturn(true);
        doAnswer(inv -> {
            Consumer<String> sink = inv.getArgument(2);
            CancelToken cancel = inv.getArgument(3);
            sink.accept("Once upon ");
            cancel.cancel();
            return new StreamResult("Once upon ", null, "test-model", true);
        }).when(llm).streamChat(any(), any(), any(), any());

        Recorder rec = run("just chatting with my friend today");

        MessageDocument reply = stored(Role.ASSISTANT).get(0);
        assertEquals(Status.INTERRUPTED, reply.getStatus());
        assertEquals("Once upon ", reply.getContent());
        assertNull(rec.done);
        assertNull(rec.error);
    }

    @Test
    void llmFailureBeforeAnyTokenFallsBackToExtractiveAnswerWhenPassagesExist() {
        retrieves(chunk("ch1", "The keeper lived alone for forty years.", 3));
        when(llm.isConfigured()).thenReturn(true);
        doAnswer(inv -> {
            throw new LlmException("boom");
        }).when(llm).streamChat(any(), any(), any(), any());

        Recorder rec = run("How long did the keeper live alone in the book?");

        assertEquals(Status.COMPLETE, stored(Role.ASSISTANT).get(0).getStatus());
        assertTrue(rec.tokens.toString().contains("The keeper lived alone for forty years."));
        assertNull(rec.error);
    }

    @Test
    void llmFailureWithNothingToFallBackOnMarksTheReplyFailedAndEmitsError() {
        when(llm.isConfigured()).thenReturn(true);
        doAnswer(inv -> {
            throw new LlmException("boom");
        }).when(llm).streamChat(any(), any(), any(), any());

        Recorder rec = run("hello");

        MessageDocument reply = stored(Role.ASSISTANT).get(0);
        assertEquals(Status.FAILED, reply.getStatus());
        assertEquals("LLM_UNAVAILABLE", rec.error.code());
        assertTrue(rec.error.retryable());
        assertEquals(reply.getId(), rec.error.messageId());
        assertNull(rec.done);
        assertEquals(1, stored(Role.USER).size(), "the user message is never lost");
    }

    @Test
    void failureMidStreamKeepsPartialTextAsInterrupted() {
        when(llm.isConfigured()).thenReturn(true);
        doAnswer(inv -> {
            Consumer<String> sink = inv.getArgument(2);
            sink.accept("Partial ");
            throw new LlmException("connection reset");
        }).when(llm).streamChat(any(), any(), any(), any());

        Recorder rec = run("hello");

        MessageDocument reply = stored(Role.ASSISTANT).get(0);
        assertEquals(Status.INTERRUPTED, reply.getStatus());
        assertEquals("Partial ", reply.getContent());
        assertEquals("STREAM_INTERRUPTED", rec.error.code());
    }

    @Test
    void nonStreamingSendReturnsBothMessages() {
        llmStreams("Hello ", "there!");

        SendMessageResponse r = service.sendMessage(USER, CONV, say("hello"));

        assertEquals(Role.USER, r.userMessage().role());
        assertEquals(Role.ASSISTANT, r.assistantMessage().role());
        assertEquals("Hello there!", r.assistantMessage().content());
        assertEquals(Status.COMPLETE, r.assistantMessage().status());
    }

    // ---------------------------------------------------------------------------------------------------------
    // regenerate
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void regenerateReplacesAFailedReply() {
        seed(Role.USER, "hello", Status.COMPLETE);
        MessageDocument failed = seed(Role.ASSISTANT, "", Status.FAILED);
        llmStreams("Hi, sorry about that.");

        ConversationService.Turn turn = service.startRegenerate(USER, CONV, failed.getId());
        Recorder rec = new Recorder();
        service.runTurn(turn, rec, new CancelToken());

        assertEquals(1, stored(Role.USER).size(), "no duplicate user message");
        List<MessageDocument> replies = stored(Role.ASSISTANT);
        assertEquals(1, replies.size());
        assertEquals(Status.COMPLETE, replies.get(0).getStatus());
        assertEquals("Hi, sorry about that.", replies.get(0).getContent());
        assertTrue(store.stream().noneMatch(m -> m.getId().equals(failed.getId())));
    }

    @Test
    void regenerateOfACompleteReplyKeepsTheOldOneUntilTheNewOneExists() {
        seed(Role.USER, "hello", Status.COMPLETE);
        MessageDocument old = seed(Role.ASSISTANT, "Old reply", Status.COMPLETE);
        llmStreams("New reply");

        service.runTurn(service.startRegenerate(USER, CONV, old.getId()), ChatEvents.Sink.NOOP, new CancelToken());

        assertEquals(List.of("New reply"), stored(Role.ASSISTANT).stream().map(MessageDocument::getContent).toList());
    }

    @Test
    void onlyTheLatestAssistantMessageCanBeRegenerated() {
        MessageDocument oldReply = seed(Role.ASSISTANT, "first", Status.COMPLETE);
        seed(Role.USER, "second question", Status.COMPLETE);
        seed(Role.ASSISTANT, "second", Status.COMPLETE);

        ApiException ex = assertThrows(ApiException.class, () -> service.startRegenerate(USER, CONV, oldReply.getId()));
        assertEquals(409, ex.getStatus().value());

        MessageDocument userMsg = stored(Role.USER).get(0);
        ApiException ex2 = assertThrows(ApiException.class, () -> service.startRegenerate(USER, CONV, userMsg.getId()));
        assertEquals(400, ex2.getStatus().value());
    }

    // ---------------------------------------------------------------------------------------------------------
    // conversations
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void createValidatesBookScopeOwnership() {
        when(conversations.save(any(ConversationDocument.class))).thenAnswer(inv -> {
            ConversationDocument c = inv.getArgument(0);
            c.setId("new1");
            return c;
        });

        ConversationResponse ok = service.create(USER, new CreateConversationRequest(
                null, true, new BookScopeInput(ScopeMode.ONE, List.of("b1"))));
        assertTrue(ok.incognito());
        assertEquals(ScopeMode.ONE, ok.bookScope().mode());
        assertEquals(ConversationService.DEFAULT_TITLE, ok.title());

        ApiException notMine = assertThrows(ApiException.class, () -> service.create(USER,
                new CreateConversationRequest(null, null, new BookScopeInput(ScopeMode.ONE, List.of("nope")))));
        assertEquals(404, notMine.getStatus().value());

        ApiException empty = assertThrows(ApiException.class, () -> service.create(USER,
                new CreateConversationRequest(null, null, new BookScopeInput(ScopeMode.ONE, List.of()))));
        assertEquals(400, empty.getStatus().value());

        ConversationResponse defaults = service.create(USER, new CreateConversationRequest(null, null, null));
        assertEquals(ScopeMode.ALL, defaults.bookScope().mode());
        assertFalse(defaults.incognito());
    }

    @Test
    void unknownConversationIsNotFound() {
        when(conversations.findByIdAndUserId("other", USER)).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.get(USER, "other"));
        assertEquals(404, ex.getStatus().value());
        assertThrows(ApiException.class, () -> service.startSend(USER, "other", say("hi")));
    }

    @Test
    void listUsesCursorPagination() {
        Instant t = Instant.parse("2026-03-01T10:00:00.123Z");
        ConversationDocument a = conv("c3", t.plusSeconds(2));
        ConversationDocument b = conv("c2", t.plusSeconds(1));
        ConversationDocument c = conv("c1", t);
        when(conversations.findByUserIdOrderByUpdatedAtDescIdDesc(eq(USER), any(Limit.class)))
                .thenReturn(List.of(a, b, c));

        CursorPage<ConversationResponse> first = service.list(USER, 2, null);

        assertEquals(List.of("c3", "c2"), first.items().stream().map(ConversationResponse::id).toList());
        assertNotNull(first.nextCursor());
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(conversations).findByUserIdOrderByUpdatedAtDescIdDesc(eq(USER), limit.capture());
        assertEquals(3, limit.getValue().max(), "fetches limit + 1 to detect a next page");

        when(conversations.findByUserIdAndUpdatedAtAndIdLessThanOrderByIdDesc(
                        eq(USER), eq(b.getUpdatedAt()), eq("c2"), any(Limit.class)))
                .thenReturn(List.of());
        when(conversations.findByUserIdAndUpdatedAtLessThanOrderByUpdatedAtDescIdDesc(
                        eq(USER), eq(b.getUpdatedAt()), any(Limit.class)))
                .thenReturn(List.of(c));

        CursorPage<ConversationResponse> second = service.list(USER, 2, first.nextCursor());
        assertEquals(List.of("c1"), second.items().stream().map(ConversationResponse::id).toList());
        assertNull(second.nextCursor());
    }

    @Test
    void invalidCursorIsABadRequest() {
        ApiException ex = assertThrows(ApiException.class, () -> service.list(USER, 10, "###"));
        assertEquals(400, ex.getStatus().value());
        assertEquals("INVALID_CURSOR", ex.getCode());
    }

    @Test
    void messagesComeBackInReadingOrderNewestPageFirst() {
        seed(Role.USER, "one", Status.COMPLETE);
        seed(Role.ASSISTANT, "two", Status.COMPLETE);
        seed(Role.USER, "three", Status.COMPLETE);

        CursorPage<MessageResponse> page = service.listMessages(USER, CONV, 2, null);

        assertEquals(List.of("two", "three"), page.items().stream().map(MessageResponse::content).toList());
        assertNotNull(page.nextCursor());
    }

    @Test
    void deleteRemovesMessagesAndConversation() {
        service.delete(USER, CONV);
        verify(messages).deleteByConversationId(CONV);
        verify(conversations).deleteById(CONV);
    }

    @Test
    void languageDetectionPrefersDevanagariThenRequestThenProfile() {
        assertEquals("hi", ConversationService.detectLanguage("\u0928\u092E\u0938\u094D\u0924\u0947", "en", "en"));
        assertEquals("fr", ConversationService.detectLanguage("bonjour", "FR", "en"));
        assertEquals("de", ConversationService.detectLanguage("hallo", null, "de"));
        assertEquals("en", ConversationService.detectLanguage("hello", " ", null));
    }

    // ---------------------------------------------------------------------------------------------------------

    private ConversationDocument conv(String id, Instant updatedAt) {
        ConversationDocument c = new ConversationDocument();
        c.setId(id);
        c.setUserId(USER);
        c.setTitle(id);
        c.setUpdatedAt(updatedAt);
        c.setCreatedAt(updatedAt);
        return c;
    }

    @SuppressWarnings("unchecked")
    // ---------------------------------------------------------------------------------------------------------
    // memory
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void memoriesAreInjectedIntoThePromptAndRecordedOnTheAssistantMessage() {
        llmStreams("Happy to help.");
        when(memoryRetrieval.retrieve(USER, "recommend something")).thenReturn(new MemoryContext(
                "# What you know about this user\n- [stated] Loves cosy mysteries", List.of("mem1", "mem2"), 20));

        Recorder rec = run("recommend something");

        assertTrue(promptCaptor().getValue().get(0).content().contains("Loves cosy mysteries"));
        assertEquals(List.of("mem1", "mem2"), stored(Role.ASSISTANT).get(0).getMemoryIdsUsed());
        assertEquals(List.of("mem1", "mem2"), rec.memoryIds);
        assertTrue(rec.events.indexOf("memory") < rec.events.indexOf("done"));
    }

    @Test
    void noMemoriesMeansNoMemoryEventAndAnEmptyList() {
        llmStreams("Hello.");
        Recorder rec = run("hi there");
        assertTrue(stored(Role.ASSISTANT).get(0).getMemoryIdsUsed().isEmpty());
        assertFalse(rec.events.contains("memory"));
        assertFalse(promptCaptor().getValue().get(0).content().contains("What you know about this user"));
    }

    @Test
    void offlineRepliesNeverReadOrClaimMemories() {
        // llm.isConfigured() is false in setUp
        run("hello offline");
        verify(memoryRetrieval, never()).retrieve(anyString(), anyString());
        assertTrue(stored(Role.ASSISTANT).get(0).getMemoryIdsUsed().isEmpty());
    }

    @Test
    void crisisReplyUsesNoMemories() {
        when(llm.isConfigured()).thenReturn(true);
        run("I want to kill myself");
        verify(memoryRetrieval, never()).retrieve(anyString(), anyString());
    }

    @Test
    void completedTurnsQueueMemoryExtractionButNeverBlockTheReply() {
        llmStreams("Sure.");
        run("tell me something");
        verify(memoryExtraction).onTurnCompleted(user, conversation);
    }

    @Test
    void chatCallsAreCountedAgainstTheSharedBudget() {
        llmStreams("Sure.");
        run("tell me something");
        verify(budget).record(eq(USER), eq(LlmBudgetService.Priority.CHAT), eq("test-model"), any(), org.mockito.ArgumentMatchers.anyLong(), eq(true));
    }

    private ArgumentCaptor<List<ChatMessage>> promptCaptor() {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm, org.mockito.Mockito.atLeastOnce()).streamChat(captor.capture(), any(), any(), any());
        return captor;
    }
}
