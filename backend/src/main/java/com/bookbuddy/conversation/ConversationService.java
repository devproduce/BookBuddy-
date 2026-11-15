package com.bookbuddy.conversation;

import com.bookbuddy.ai.CancelToken;
import com.bookbuddy.ai.ChatMessage;
import com.bookbuddy.ai.ChatOptions;
import com.bookbuddy.ai.LlmException;
import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.ai.StreamResult;
import com.bookbuddy.ai.TokenUsage;
import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.book.BookRepository;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.conversation.ChatEvents.Done;
import com.bookbuddy.conversation.ChatEvents.Meta;
import com.bookbuddy.conversation.ChatEvents.Sink;
import com.bookbuddy.conversation.ConversationDocument.BookScope;
import com.bookbuddy.conversation.ConversationDocument.ScopeMode;
import com.bookbuddy.conversation.ConversationDtos.BookScopeInput;
import com.bookbuddy.conversation.ConversationDtos.ConversationResponse;
import com.bookbuddy.conversation.ConversationDtos.CreateConversationRequest;
import com.bookbuddy.conversation.ConversationDtos.CursorPage;
import com.bookbuddy.conversation.ConversationDtos.MessageResponse;
import com.bookbuddy.conversation.ConversationDtos.SendMessageRequest;
import com.bookbuddy.conversation.ConversationDtos.SendMessageResponse;
import com.bookbuddy.conversation.ConversationDtos.UpdateConversationRequest;
import com.bookbuddy.conversation.IntentRouter.Intent;
import com.bookbuddy.conversation.MessageDocument.InputMode;
import com.bookbuddy.conversation.MessageDocument.Role;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.memory.MemoryContext;
import com.bookbuddy.memory.MemoryExtractionService;
import com.bookbuddy.memory.MemoryRetrievalService;
import com.bookbuddy.quota.LlmBudgetService;
import com.bookbuddy.quota.LlmBudgetService.Priority;
import com.bookbuddy.rag.QuoteVerifier;
import com.bookbuddy.rag.RagDtos.Citation;
import com.bookbuddy.rag.RagService;
import com.bookbuddy.rag.RagService.Retrieval;
import com.bookbuddy.safety.CrisisResponseService;
import com.bookbuddy.safety.CrisisResponseService.CrisisResponse;
import com.bookbuddy.safety.RiskClassifier;
import com.bookbuddy.safety.RiskLevel;
import com.bookbuddy.safety.SafetyEventLogger;
import com.bookbuddy.settings.ConsentPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Conversations and the chat pipeline: persist the user message, safety pre-check, intent routing, optional book
 * retrieval, streamed generation, quote verification, persistence.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    public static final String DEFAULT_TITLE = "New conversation";
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int HISTORY_MAX_MESSAGES = 16;
    static final int HISTORY_MAX_TOKENS = 2500;
    static final int EXCERPT_CHARS = 1600;
    static final String CRISIS_PROMPT_VERSION = "crisis.static.v1";

    static final String OFFLINE_MODEL = "offline-extractive";

    static final String OFFLINE_REPLY = "I'm here and I'm listening. I can't reach my language model right now, so I "
            + "can't give a full reply, but you can still ask me about a specific book and I'll pull the most "
            + "relevant passages from your library. If you're going through something heavy, talking to someone you "
            + "trust can really help.";

    private static final Pattern DEVANAGARI = Pattern.compile("[\\u0900-\\u097F]");
    private static final Pattern CITE_MARK = Pattern.compile("\\s?\\[\\d+\\]");
    private static final Pattern WORD = Pattern.compile("\\S+\\s*");

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final UserRepository users;
    private final BookRepository books;
    private final RagService rag;
    private final LlmProvider llm;
    private final RiskClassifier riskClassifier;
    private final CrisisResponseService crisis;
    private final SafetyEventLogger safetyLogger;
    private final QuoteVerifier quoteVerifier;
    private final IntentRouter intentRouter;
    private final PromptLoader prompts;
    private final RollingSummaryService summaries;
    private final MemoryRetrievalService memoryRetrieval;
    private final MemoryExtractionService memoryExtraction;
    private final LlmBudgetService budget;

    /** Pause between words when streaming an offline (extractive) answer. */
    private volatile long wordDelayMs = 18;

    public ConversationService(
            ConversationRepository conversations,
            MessageRepository messages,
            UserRepository users,
            BookRepository books,
            RagService rag,
            LlmProvider llm,
            RiskClassifier riskClassifier,
            CrisisResponseService crisis,
            SafetyEventLogger safetyLogger,
            QuoteVerifier quoteVerifier,
            IntentRouter intentRouter,
            PromptLoader prompts,
            RollingSummaryService summaries,
            MemoryRetrievalService memoryRetrieval,
            MemoryExtractionService memoryExtraction,
            LlmBudgetService budget) {
        this.conversations = conversations;
        this.messages = messages;
        this.users = users;
        this.books = books;
        this.rag = rag;
        this.llm = llm;
        this.riskClassifier = riskClassifier;
        this.crisis = crisis;
        this.safetyLogger = safetyLogger;
        this.quoteVerifier = quoteVerifier;
        this.intentRouter = intentRouter;
        this.prompts = prompts;
        this.summaries = summaries;
        this.memoryRetrieval = memoryRetrieval;
        this.memoryExtraction = memoryExtraction;
        this.budget = budget;
    }

    void setWordDelayMs(long wordDelayMs) {
        this.wordDelayMs = wordDelayMs;
    }

    /** One accepted user message awaiting (or being given) a reply. */
    public record Turn(
            ConversationDocument conversation,
            UserDocument user,
            MessageDocument userMessage,
            /** Non-null when regenerating: the assistant message this turn replaces. */
            MessageDocument replaced,
            String language) {}

    // ---------------------------------------------------------------------------------------------------------
    // Conversation CRUD
    // ---------------------------------------------------------------------------------------------------------

    public ConversationResponse create(String userId, CreateConversationRequest request) {
        ConversationDocument c = new ConversationDocument();
        c.setUserId(userId);
        c.setTitle(request.title() == null || request.title().isBlank() ? DEFAULT_TITLE : request.title().strip());
        c.setIncognito(Boolean.TRUE.equals(request.incognito()));
        c.setBookScope(resolveScope(userId, request.bookScope()));
        Instant now = now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return ConversationResponse.from(conversations.save(c));
    }

    public CursorPage<ConversationResponse> list(String userId, Integer limit, String cursor) {
        int n = clampLimit(limit);
        Fetcher<ConversationDocument> fetcher = new Fetcher<>() {
            @Override
            public List<ConversationDocument> first(int count) {
                return conversations.findByUserIdOrderByUpdatedAtDescIdDesc(userId, Limit.of(count));
            }

            @Override
            public List<ConversationDocument> tied(Instant at, String id, int count) {
                return conversations.findByUserIdAndUpdatedAtAndIdLessThanOrderByIdDesc(userId, at, id, Limit.of(count));
            }

            @Override
            public List<ConversationDocument> older(Instant at, int count) {
                return conversations.findByUserIdAndUpdatedAtLessThanOrderByUpdatedAtDescIdDesc(
                        userId, at, Limit.of(count));
            }
        };
        Page<ConversationDocument> page = fetchPage(fetcher, cursor, n, ConversationDocument::getUpdatedAt,
                ConversationDocument::getId);
        return new CursorPage<>(page.items().stream().map(ConversationResponse::from).toList(), page.nextCursor());
    }

    public ConversationResponse get(String userId, String id) {
        return ConversationResponse.from(find(userId, id));
    }

    public ConversationResponse update(String userId, String id, UpdateConversationRequest request) {
        ConversationDocument c = find(userId, id);
        if (request.title() != null) {
            String t = request.title().strip();
            if (t.isEmpty()) {
                throw ApiException.badRequest("VALIDATION_ERROR", "title must not be blank");
            }
            c.setTitle(t);
        }
        if (request.incognito() != null) {
            c.setIncognito(request.incognito());
        }
        if (request.bookScope() != null) {
            c.setBookScope(resolveScope(userId, request.bookScope()));
        }
        return ConversationResponse.from(conversations.save(c));
    }

    public void delete(String userId, String id) {
        ConversationDocument c = find(userId, id);
        messages.deleteByConversationId(c.getId());
        conversations.deleteById(c.getId());
        if (memoryExtraction != null) {
            memoryExtraction.onConversationDeleted(userId, c.getId()); // memories forget their source
        }
    }

    public CursorPage<MessageResponse> listMessages(String userId, String conversationId, Integer limit, String cursor) {
        find(userId, conversationId);
        int n = clampLimit(limit);
        Fetcher<MessageDocument> fetcher = new Fetcher<>() {
            @Override
            public List<MessageDocument> first(int count) {
                return messages.findByConversationIdAndUserIdOrderByCreatedAtDescIdDesc(
                        conversationId, userId, Limit.of(count));
            }

            @Override
            public List<MessageDocument> tied(Instant at, String id, int count) {
                return messages.findByConversationIdAndUserIdAndCreatedAtAndIdLessThanOrderByIdDesc(
                        conversationId, userId, at, id, Limit.of(count));
            }

            @Override
            public List<MessageDocument> older(Instant at, int count) {
                return messages.findByConversationIdAndUserIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
                        conversationId, userId, at, Limit.of(count));
            }
        };
        Page<MessageDocument> page = fetchPage(fetcher, cursor, n, MessageDocument::getCreatedAt, MessageDocument::getId);
        // The page holds the newest N messages (newest first); the client wants them in reading order.
        List<MessageResponse> items = new ArrayList<>(page.items().stream().map(MessageResponse::from).toList());
        Collections.reverse(items);
        return new CursorPage<>(items, page.nextCursor());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------------------------

    /** Non-streaming send: runs the whole turn and returns both messages. */
    public SendMessageResponse sendMessage(String userId, String conversationId, SendMessageRequest request) {
        Turn turn = startSend(userId, conversationId, request);
        MessageDocument assistant = runTurn(turn, Sink.NOOP, new CancelToken());
        return new SendMessageResponse(MessageResponse.from(turn.userMessage()), MessageResponse.from(assistant));
    }

    /** Non-streaming regenerate. */
    public MessageResponse regenerate(String userId, String conversationId, String messageId) {
        Turn turn = startRegenerate(userId, conversationId, messageId);
        return MessageResponse.from(runTurn(turn, Sink.NOOP, new CancelToken()));
    }

    /**
     * Validates consent and ownership, then persists the USER message. Always runs before any other step so the
     * user's words are never lost, whatever happens to the reply.
     */
    public Turn startSend(String userId, String conversationId, SendMessageRequest request) {
        UserDocument user = requireConsentedUser(userId);
        ConversationDocument conversation = find(userId, conversationId);
        String content = request.content() == null ? "" : request.content().strip();
        if (content.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "content must not be blank");
        }
        String language = detectLanguage(content, request.language(), user.getDefaultLanguage());

        MessageDocument userMessage = new MessageDocument();
        userMessage.setConversationId(conversation.getId());
        userMessage.setUserId(userId);
        userMessage.setRole(Role.USER);
        userMessage.setContent(content);
        userMessage.setStatus(Status.COMPLETE);
        userMessage.setLanguage(language);
        userMessage.setInputMode(request.inputMode() == null ? InputMode.TEXT : request.inputMode());
        userMessage.setCreatedAt(now());
        userMessage = messages.save(userMessage);

        if (conversation.getTitle() == null || conversation.getTitle().isBlank()
                || DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(titleFrom(content));
        }
        touch(conversation);
        return new Turn(conversation, user, userMessage, null, language);
    }

    /** Re-answers the latest user message, replacing the latest assistant message {@code messageId}. */
    public Turn startRegenerate(String userId, String conversationId, String messageId) {
        UserDocument user = requireConsentedUser(userId);
        ConversationDocument conversation = find(userId, conversationId);
        MessageDocument target = messages
                .findByIdAndConversationIdAndUserId(messageId, conversationId, userId)
                .orElseThrow(() -> ApiException.notFound("MESSAGE_NOT_FOUND", "Message not found"));
        if (target.getRole() != Role.ASSISTANT) {
            throw ApiException.badRequest("NOT_ASSISTANT_MESSAGE", "Only assistant messages can be regenerated");
        }
        List<MessageDocument> latest =
                messages.findByConversationIdAndUserIdOrderByCreatedAtDescIdDesc(conversationId, userId, Limit.of(2));
        if (latest.isEmpty() || !messageId.equals(latest.get(0).getId())) {
            throw ApiException.conflict("NOT_LATEST_MESSAGE", "Only the latest reply can be regenerated");
        }
        if (latest.size() < 2 || latest.get(1).getRole() != Role.USER) {
            throw ApiException.conflict("NO_USER_MESSAGE", "There is no question to answer again");
        }
        MessageDocument userMessage = latest.get(1);
        String language = detectLanguage(userMessage.getContent(), userMessage.getLanguage(), user.getDefaultLanguage());
        return new Turn(conversation, user, userMessage, target, language);
    }

    /**
     * Produces the assistant reply for {@code turn}, emitting events to {@code sink}. Blocks until the reply is
     * finished, interrupted or failed. Never throws for generation problems: those become a FAILED / INTERRUPTED
     * message plus an {@code error} event.
     */
    public MessageDocument runTurn(Turn turn, Sink sink, CancelToken cancel) {
        ConversationDocument conversation = turn.conversation();
        MessageDocument userMessage = turn.userMessage();
        String userId = conversation.getUserId();

        // 1. safety pre-check (the user message is already persisted)
        RiskLevel level = classify(userMessage.getContent());
        safetyLogger.log(level);
        if (userMessage.getSafetyLevel() != level) {
            userMessage.setSafetyLevel(level);
            messages.save(userMessage);
        }

        // A reply that was not good enough to keep goes away now; a good one is replaced only once the new one exists.
        MessageDocument replaced = turn.replaced();
        if (replaced != null && replaced.getStatus() != Status.COMPLETE) {
            messages.deleteById(replaced.getId());
            replaced = null;
        }

        // 2. placeholder assistant message: if anything goes wrong from here on it is saved as INTERRUPTED / FAILED
        MessageDocument assistant = new MessageDocument();
        assistant.setConversationId(conversation.getId());
        assistant.setUserId(userId);
        assistant.setRole(Role.ASSISTANT);
        assistant.setContent("");
        assistant.setStatus(Status.INTERRUPTED);
        assistant.setSafetyLevel(level);
        assistant.setLanguage(turn.language());
        assistant.setInputMode(InputMode.TEXT);
        Instant created = now();
        if (userMessage.getCreatedAt() != null && !created.isAfter(userMessage.getCreatedAt())) {
            created = userMessage.getCreatedAt().plusMillis(1); // keep reading order within the same millisecond
        }
        assistant.setCreatedAt(created);
        assistant = messages.save(assistant);
        sink.meta(new Meta(assistant.getId(), userMessage.getId(), conversation.getId()));

        try {
            if (level == RiskLevel.ACUTE) {
                return acuteReply(turn, assistant, sink, replaced);
            }
            return generate(turn, assistant, level, sink, cancel, replaced);
        } catch (RuntimeException e) {
            log.warn("Chat turn failed: {}", e.getClass().getSimpleName());
            assistant.setStatus(Status.FAILED);
            messages.save(assistant);
            touch(conversation);
            String code = e instanceof ApiException api ? api.getCode() : "GENERATION_FAILED";
            String message = e instanceof ApiException api ? api.getMessage()
                    : "I couldn't finish that reply. Please try again.";
            sink.error(new ChatEvents.Error(code, message, true, assistant.getId()));
            return assistant;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // ACUTE path
    // ---------------------------------------------------------------------------------------------------------

    private MessageDocument acuteReply(Turn turn, MessageDocument assistant, Sink sink, MessageDocument replaced) {
        CrisisResponse response = crisis.build(turn.user(), turn.language());
        sink.safety(new ChatEvents.Safety(RiskLevel.ACUTE, response.resources()));
        sink.token(response.message());

        assistant.setContent(response.message());
        assistant.setStatus(Status.COMPLETE);
        assistant.setModelId("static");
        assistant.setPromptVersion(CRISIS_PROMPT_VERSION);
        assistant = messages.save(assistant);
        finishTurn(turn, replaced);
        sink.done(new Done(assistant.getId(), Status.COMPLETE, assistant.getContent(), null, "static"));
        return assistant;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Normal path
    // ---------------------------------------------------------------------------------------------------------

    private MessageDocument generate(
            Turn turn, MessageDocument assistant, RiskLevel level, Sink sink, CancelToken cancel,
            MessageDocument replaced) {
        ConversationDocument conversation = turn.conversation();
        String userId = conversation.getUserId();
        String question = turn.userMessage().getContent();
        BookScope scope = conversation.getBookScope() == null ? BookScope.all() : conversation.getBookScope();

        if (level == RiskLevel.ELEVATED) {
            sink.safety(new ChatEvents.Safety(RiskLevel.ELEVATED, crisis.resources(turn.user())));
        }

        Intent intent = intentRouter.route(question, scope.mode() == ScopeMode.ONE);
        boolean retrieve = scope.mode() != ScopeMode.NONE && intent.needsBooks();
        Retrieval retrieval = retrieve ? rag.retrieve(userId, question, conversation.scopedBookIds()) : null;
        boolean hasPassages = retrieval != null && !retrieval.isEmpty();

        MemoryContext memory = llm.isConfigured() ? recall(userId, question) : MemoryContext.EMPTY;
        List<ChatMessage> prompt = buildPrompt(turn, level, scope, retrieve, retrieval, memory);
        StringBuilder out = new StringBuilder();
        String modelId = null;
        TokenUsage usage = null;
        boolean failedMidStream = false;

        if (cancel.isCancelled()) {
            return interrupted(turn, assistant, out, null);
        }

        if (retrieve && !hasPassages && intent == Intent.BOOK_QUESTION) {
            // nothing relevant in the library: say so, without asking the model to guess
            streamWords(RagService.NO_ANSWER, out, sink, cancel);
            modelId = OFFLINE_MODEL;
        } else if (llm.isConfigured()) {
            assistant.setMemoryIdsUsed(new ArrayList<>(memory.memoryIds()));
            long started = System.currentTimeMillis();
            try {
                StreamResult result = llm.streamChat(prompt, ChatOptions.defaults(), token -> {
                    out.append(token);
                    sink.token(token);
                }, cancel);
                if (result != null) {
                    modelId = result.modelId();
                    usage = result.usage();
                }
                recordChatUsage(userId, modelId, usage, prompt, out, started, true);
            } catch (LlmException e) {
                log.warn("LLM stream failed ({}); tokens delivered: {}", e.getMessage(), out.length() > 0);
                recordChatUsage(userId, modelId, null, prompt, out, started, false);
                if (out.length() > 0) {
                    failedMidStream = true;
                } else if (hasPassages) {
                    assistant.setMemoryIdsUsed(new ArrayList<>()); // the model never saw them
                    streamWords(rag.extractiveAnswer(question, retrieval.chunks(), retrieval.citations()),
                            out, sink, cancel);
                    modelId = OFFLINE_MODEL;
                } else {
                    throw new ApiException(
                            org.springframework.http.HttpStatus.BAD_GATEWAY,
                            "LLM_UNAVAILABLE",
                            "I couldn't reach the language model. Please try again in a moment.",
                            true,
                            null);
                }
            }
        } else if (hasPassages) {
            streamWords(rag.extractiveAnswer(question, retrieval.chunks(), retrieval.citations()), out, sink, cancel);
            modelId = OFFLINE_MODEL;
        } else {
            streamWords(OFFLINE_REPLY, out, sink, cancel);
            modelId = OFFLINE_MODEL;
        }

        if (cancel.isCancelled()) {
            return interrupted(turn, assistant, out, modelId);
        }
        if (failedMidStream) {
            MessageDocument saved = interrupted(turn, assistant, out, modelId);
            sink.error(new ChatEvents.Error(
                    "STREAM_INTERRUPTED", "The reply was cut short. You can retry it.", true, saved.getId()));
            return saved;
        }

        // 7. quote verification on the final text (only meaningful when passages were supplied)
        String finalText = out.toString().strip();
        List<MessageCitation> citations = List.of();
        if (hasPassages) {
            finalText = quoteVerifier.verify(finalText, retrieval.texts()).text();
            citations = toCitations(retrieval);
            sink.citations(citations);
        }

        if (usage == null) {
            usage = TokenUsage.of(estimatePromptTokens(prompt), TokenEstimator.estimate(finalText), true);
        }
        assistant.setContent(finalText);
        assistant.setStatus(Status.COMPLETE);
        assistant.setCitations(new ArrayList<>(citations));
        assistant.setModelId(modelId);
        assistant.setPromptVersion(prompts.chatPromptId());
        assistant.setTokenUsage(usage);
        assistant = messages.save(assistant);
        finishTurn(turn, replaced);
        if (assistant.getMemoryIdsUsed() != null && !assistant.getMemoryIdsUsed().isEmpty()) {
            sink.memory(List.copyOf(assistant.getMemoryIdsUsed()));
        }
        sink.done(new Done(assistant.getId(), Status.COMPLETE, finalText, usage, modelId));
        return assistant;
    }

    private MessageDocument interrupted(Turn turn, MessageDocument assistant, StringBuilder out, String modelId) {
        assistant.setContent(out.toString());
        assistant.setStatus(Status.INTERRUPTED);
        assistant.setModelId(modelId);
        assistant.setPromptVersion(prompts.chatPromptId());
        MessageDocument saved = messages.save(assistant);
        touch(turn.conversation());
        return saved;
    }

    /** Post-reply bookkeeping: drop the replaced reply, bump the conversation, schedule the rolling summary. */
    private void finishTurn(Turn turn, MessageDocument replaced) {
        if (replaced != null) {
            messages.deleteById(replaced.getId());
        }
        touch(turn.conversation());
        try {
            summaries.summarizeAsync(turn.conversation().getUserId(), turn.conversation().getId());
        } catch (RejectedExecutionException e) {
            log.debug("Summary skipped: executor busy");
        }
        if (memoryExtraction != null) {
            // queues a durable background job at most; extraction itself never runs on the reply path
            memoryExtraction.onTurnCompleted(turn.user(), turn.conversation());
        }
    }

    /** Memories for this message; memory is an enhancement, so any problem just means "no memories". */
    private MemoryContext recall(String userId, String question) {
        if (memoryRetrieval == null) {
            return MemoryContext.EMPTY;
        }
        MemoryContext context = memoryRetrieval.retrieve(userId, question);
        return context == null ? MemoryContext.EMPTY : context;
    }

    private void recordChatUsage(
            String userId, String modelId, TokenUsage usage, List<ChatMessage> prompt, StringBuilder out,
            long startedMillis, boolean ok) {
        if (budget == null) {
            return;
        }
        TokenUsage tokens = usage != null
                ? usage
                : TokenUsage.of(estimatePromptTokens(prompt), TokenEstimator.estimate(out.toString()), true);
        budget.record(userId, Priority.CHAT, modelId, tokens, System.currentTimeMillis() - startedMillis, ok);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Prompt assembly
    // ---------------------------------------------------------------------------------------------------------

    List<ChatMessage> buildPrompt(
            Turn turn, RiskLevel level, BookScope scope, boolean retrieve, Retrieval retrieval,
            MemoryContext memory) {
        ConversationDocument conversation = turn.conversation();
        String question = turn.userMessage().getContent();

        StringBuilder system = new StringBuilder(prompts.chatSystemPrompt());
        system.append("\n\n# This conversation\n");
        system.append("- Preferred language: ").append(turn.language() == null ? "en" : turn.language())
                .append(". Still mirror the language and script of the latest user message.\n");
        if (scope.mode() == ScopeMode.NONE) {
            system.append("- The user turned book lookup off for this chat. Do not claim to consult their books.\n");
        } else if (retrieve && (retrieval == null || retrieval.isEmpty())) {
            system.append("- No relevant passages were found in the user's books for this message.\n");
        }
        if (level == RiskLevel.ELEVATED) {
            system.append("- Safety note: the user may be in distress. Be especially gentle, do not minimise what "
                    + "they feel, do not diagnose, and mention naturally that someone they trust or a helpline can "
                    + "help.\n");
        }
        if (conversation.getSummary() != null && !conversation.getSummary().isBlank()) {
            system.append("\n# Summary of earlier turns (context only)\n").append(conversation.getSummary().strip())
                    .append('\n');
        }
        if (memory != null && !memory.isEmpty()) {
            system.append('\n').append(memory.promptBlock()).append('\n');
        }

        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(system.toString()));
        prompt.addAll(history(turn));
        boolean withPassages = retrieval != null && !retrieval.isEmpty();
        prompt.add(ChatMessage.user(
                withPassages ? RagService.buildUserPrompt(question, retrieval.chunks(), retrieval.citations())
                        : question));
        return prompt;
    }

    /** Recent complete turns before the current user message, newest-first trimmed to the token budget. */
    List<ChatMessage> history(Turn turn) {
        ConversationDocument conversation = turn.conversation();
        List<MessageDocument> newestFirst = messages.findByConversationIdAndUserIdOrderByCreatedAtDescIdDesc(
                conversation.getId(), conversation.getUserId(), Limit.of(HISTORY_MAX_MESSAGES + 3));
        List<MessageDocument> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);

        int end = chronological.size();
        for (int i = 0; i < chronological.size(); i++) {
            if (chronological.get(i).getId().equals(turn.userMessage().getId())) {
                end = i;
                break;
            }
        }
        int start = 0;
        String lastSummarized = conversation.getLastSummarizedMessageId();
        if (lastSummarized != null) {
            for (int i = 0; i < end; i++) {
                if (lastSummarized.equals(chronological.get(i).getId())) {
                    start = i + 1;
                }
            }
        }

        List<ChatMessage> picked = new ArrayList<>();
        int budget = HISTORY_MAX_TOKENS;
        for (int i = end - 1; i >= start && picked.size() < HISTORY_MAX_MESSAGES; i--) {
            MessageDocument m = chronological.get(i);
            if (m.getStatus() != Status.COMPLETE || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            if (m.getRole() != Role.USER && m.getRole() != Role.ASSISTANT) {
                continue;
            }
            // [n] markers pointed at passages of an earlier turn; they would mislead this turn's numbering
            String text = m.getRole() == Role.ASSISTANT ? CITE_MARK.matcher(m.getContent()).replaceAll("") : m.getContent();
            budget -= TokenEstimator.estimate(text);
            if (budget < 0 && !picked.isEmpty()) {
                break;
            }
            picked.add(m.getRole() == Role.USER ? ChatMessage.user(text) : ChatMessage.assistant(text));
        }
        Collections.reverse(picked);
        return picked;
    }

    private static int estimatePromptTokens(List<ChatMessage> prompt) {
        int total = 0;
        for (ChatMessage m : prompt) {
            total += TokenEstimator.estimate(m.content()) + 4;
        }
        return total;
    }

    private static List<MessageCitation> toCitations(Retrieval retrieval) {
        List<MessageCitation> out = new ArrayList<>(retrieval.chunks().size());
        for (int i = 0; i < retrieval.chunks().size(); i++) {
            Citation c = retrieval.citations().get(i);
            String text = retrieval.chunks().get(i).getContent();
            out.add(new MessageCitation(
                    i + 1, c.chunkId(), c.bookId(), c.bookTitle(), c.pageStart(), c.pageEnd(), clip(text)));
        }
        return out;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= EXCERPT_CHARS ? t : t.substring(0, EXCERPT_CHARS) + "...";
    }

    // ---------------------------------------------------------------------------------------------------------
    // Streaming helpers
    // ---------------------------------------------------------------------------------------------------------

    /** Streams a locally built answer word by word so offline mode feels the same as the live model. */
    private void streamWords(String text, StringBuilder out, Sink sink, CancelToken cancel) {
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            if (cancel.isCancelled()) {
                return;
            }
            out.append(m.group());
            sink.token(m.group());
            if (wordDelayMs > 0) {
                try {
                    Thread.sleep(wordDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancel.cancel();
                    return;
                }
            }
        }
    }

    private RiskLevel classify(String text) {
        try {
            return riskClassifier.classify(text);
        } catch (RuntimeException e) {
            // never let a classifier bug turn into a missed crisis: fall back to the deterministic rules
            log.warn("Risk classifier failed ({}); using rules", e.getClass().getSimpleName());
            return riskClassifier.classifyByRules(text);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pagination
    // ---------------------------------------------------------------------------------------------------------

    private interface Fetcher<T> {
        List<T> first(int count);

        List<T> tied(Instant at, String id, int count);

        List<T> older(Instant at, int count);
    }

    private record Page<T>(List<T> items, String nextCursor) {}

    private static <T> Page<T> fetchPage(
            Fetcher<T> fetcher,
            String cursor,
            int limit,
            java.util.function.Function<T, Instant> timestamp,
            java.util.function.Function<T, String> id) {
        int want = limit + 1;
        List<T> items;
        if (cursor == null || cursor.isBlank()) {
            items = new ArrayList<>(fetcher.first(want));
        } else {
            Cursor c = decodeCursor(cursor);
            items = new ArrayList<>(fetcher.tied(c.at(), c.id(), want));
            if (items.size() < want) {
                items.addAll(fetcher.older(c.at(), want - items.size()));
            }
        }
        boolean more = items.size() > limit;
        List<T> pageItems = more ? new ArrayList<>(items.subList(0, limit)) : items;
        String next = null;
        if (more) {
            T last = pageItems.get(pageItems.size() - 1);
            next = encodeCursor(timestamp.apply(last), id.apply(last));
        }
        return new Page<>(pageItems, next);
    }

    private record Cursor(Instant at, String id) {}

    static String encodeCursor(Instant at, String id) {
        String raw = at.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            if (colon <= 0 || colon == raw.length() - 1) {
                throw new IllegalArgumentException("bad cursor");
            }
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, colon))), raw.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "Invalid cursor");
        }
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------------------------------------------

    private UserDocument requireConsentedUser(String userId) {
        UserDocument user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
        ConsentPolicy.require(user);
        return user;
    }

    private ConversationDocument find(String userId, String id) {
        return conversations
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found"));
    }

    private BookScope resolveScope(String userId, BookScopeInput input) {
        if (input == null || input.mode() == null || input.mode() == ScopeMode.ALL) {
            return BookScope.all();
        }
        if (input.mode() == ScopeMode.NONE) {
            return new BookScope(ScopeMode.NONE, List.of());
        }
        List<String> ids = input.bookIds() == null ? List.of() : input.bookIds().stream().distinct().toList();
        if (ids.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "bookScope.bookIds is required when mode is ONE");
        }
        for (String bookId : ids) {
            if (books.findByIdAndUserId(bookId, userId).isEmpty()) {
                throw ApiException.notFound("BOOK_NOT_FOUND", "Book not found");
            }
        }
        return new BookScope(ScopeMode.ONE, ids);
    }

    /** Bumps {@code updatedAt} and re-counts messages. */
    private void touch(ConversationDocument conversation) {
        conversation.setMessageCount((int) messages.countByConversationId(conversation.getId()));
        conversation.setUpdatedAt(now());
        conversations.save(conversation);
    }

    static String detectLanguage(String text, String requested, String userDefault) {
        if (text != null && DEVANAGARI.matcher(text).find()) {
            return "hi";
        }
        if (requested != null && !requested.isBlank()) {
            return requested.strip().toLowerCase();
        }
        return userDefault == null || userDefault.isBlank() ? "en" : userDefault;
    }

    static String titleFrom(String content) {
        String oneLine = content.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60).stripTrailing() + "\u2026";
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
