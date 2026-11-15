package com.bookbuddy.memory;

import com.bookbuddy.ai.LlmProvider;
import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.auth.UserRepository;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.embedding.EmbeddingProvider;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.jobs.JobQueueDocument;
import com.bookbuddy.jobs.JobQueueService;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Classification;
import com.bookbuddy.memory.MemoryDocument.Sensitivity;
import com.bookbuddy.memory.MemoryDocument.Status;
import com.bookbuddy.memory.MemoryDtos.CreateMemoryRequest;
import com.bookbuddy.memory.MemoryDtos.JobResponse;
import com.bookbuddy.memory.MemoryDtos.MemoryResponse;
import com.bookbuddy.memory.MemoryDtos.ProfileResponse;
import com.bookbuddy.memory.MemoryDtos.UpdateMemoryRequest;
import com.bookbuddy.memory.MemoryRetrievalService.CoreProfile;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/** Everything a user can do to their own memories: look, add, correct, confirm, pin, delete, clear, rebuild. */
@Service
public class MemoryService {

    static final int MAX_MEMORIES_PER_USER = 500;

    private static final Map<Category, Double> DEFAULT_IMPORTANCE = Map.of(
            Category.ABOUT_ME, 0.8,
            Category.GOALS, 0.7,
            Category.PREFERENCES, 0.7,
            Category.INTERESTS, 0.6,
            Category.EXPERIENCES, 0.5,
            Category.EMOTIONAL_CONTEXT, 0.5,
            Category.OBSERVED_PATTERNS, 0.4);

    private final MemoryRepository repository;
    private final EmbeddingProvider embeddings;
    private final MemoryRetrievalService retrieval;
    private final MemoryProperties props;
    private final UserRepository users;
    private final JobQueueService jobs;
    private final LlmProvider llm;

    public MemoryService(
            MemoryRepository repository,
            EmbeddingProvider embeddings,
            MemoryRetrievalService retrieval,
            MemoryProperties props,
            UserRepository users,
            JobQueueService jobs,
            LlmProvider llm) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.retrieval = retrieval;
        this.props = props;
        this.users = users;
        this.jobs = jobs;
        this.llm = llm;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------------------

    /**
     * @param status   ACTIVE (default), ARCHIVED, EXPIRED or DELETED; {@code null} means ACTIVE. Overdue memories count
     *                 as EXPIRED even before the maintenance job flips them.
     * @param all      when true, every status is returned
     * @param ids      when non-empty, only these memories (any status): used by the "why this answer" panel
     */
    public List<MemoryResponse> list(
            String userId, Category category, Status status, boolean all, Collection<String> ids) {
        Instant now = Instant.now();
        List<MemoryDocument> found;
        if (ids != null && !ids.isEmpty()) {
            found = repository.findByUserIdAndIdIn(userId, ids);
        } else if (category != null) {
            found = repository.findByUserIdAndCategoryOrderByUpdatedAtDesc(userId, category);
        } else {
            found = repository.findByUserIdOrderByUpdatedAtDesc(userId);
        }
        Status wanted = status == null ? Status.ACTIVE : status;
        boolean byIds = ids != null && !ids.isEmpty();
        List<MemoryResponse> out = new ArrayList<>();
        for (MemoryDocument m : found) {
            MemoryResponse r = MemoryResponse.from(m, now);
            if (byIds || all || r.status() == wanted) {
                out.add(r);
            }
        }
        return out;
    }

    public ProfileResponse profile(String userId) {
        CoreProfile core = retrieval.coreProfile(userId);
        Instant now = Instant.now();
        return new ProfileResponse(
                core.text(),
                core.estimatedTokens(),
                props.coreProfileTokens(),
                core.items().stream().map(m -> MemoryResponse.from(m, now)).toList());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------------------

    /** A memory the user typed (or answered in onboarding): stated, verified, never inferred. */
    public MemoryResponse create(String userId, CreateMemoryRequest request) {
        String content = MemoryPolicy.sanitize(request.content());
        if (content.length() < MemoryPolicy.MIN_CONTENT_CHARS) {
            throw ApiException.badRequest("VALIDATION_ERROR", "content is too short");
        }
        if (repository.countByUserIdAndStatus(userId, Status.ACTIVE) >= MAX_MEMORIES_PER_USER) {
            throw ApiException.conflict("MEMORY_LIMIT", "You have reached the limit of saved memories. Delete some first.");
        }
        Instant now = now();
        String normalized = MemoryPolicy.normalize(content);
        for (MemoryDocument existing : repository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, Status.ACTIVE)) {
            if (existing.getCategory() == request.category()
                    && normalized.equals(MemoryPolicy.normalize(existing.getContent()))) {
                throw ApiException.conflict("MEMORY_DUPLICATE", "You already have this memory.");
            }
        }
        boolean pinned = Boolean.TRUE.equals(request.pinned());
        MemoryDocument m = new MemoryDocument();
        m.setId(new ObjectId().toHexString());
        m.setUserId(userId);
        m.setCategory(request.category());
        m.setContent(content);
        m.setClassification(Classification.EXPLICIT);
        m.setConfidence(1.0);
        m.setImportance(DEFAULT_IMPORTANCE.getOrDefault(request.category(), 0.5));
        m.setEmbedding(VectorMath.toList(embeddings.embedPassage(content)));
        m.setEmbeddingModel(embeddings.modelName());
        m.setSensitivity(SensitiveTopics.isSensitive(content) ? Sensitivity.SENSITIVE : Sensitivity.NORMAL);
        m.setUserVerified(true);
        m.setPinned(pinned);
        m.setStatus(Status.ACTIVE);
        m.setExpiresAt(MemoryPolicy.defaultExpiry(request.category(), pinned, now, props.emotionalContextDays()));
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return MemoryResponse.from(repository.save(m), now);
    }

    /** Editing sets {@code userVerified}: the user's wording is the truth now. */
    public MemoryResponse update(String userId, String id, UpdateMemoryRequest request) {
        MemoryDocument m = find(userId, id);
        if (m.getStatus() != Status.ACTIVE && m.getStatus() != Status.EXPIRED) {
            throw ApiException.conflict("MEMORY_NOT_ACTIVE", "This memory was replaced or removed and can't be edited.");
        }
        Instant now = now();

        if (request.content() != null) {
            String content = MemoryPolicy.sanitize(request.content());
            if (content.length() < MemoryPolicy.MIN_CONTENT_CHARS) {
                throw ApiException.badRequest("VALIDATION_ERROR", "content is too short");
            }
            if (!content.equals(m.getContent())) {
                m.setContent(content);
                m.setEmbedding(VectorMath.toList(embeddings.embedPassage(content)));
                m.setEmbeddingModel(embeddings.modelName());
                if (SensitiveTopics.isSensitive(content)) {
                    m.setSensitivity(Sensitivity.SENSITIVE);
                }
            }
            m.setClassification(Classification.EXPLICIT);
            m.setConfidence(1.0);
            m.setUserVerified(true);
        }
        if (request.category() != null) {
            m.setCategory(request.category());
            m.setUserVerified(true);
        }
        if (request.sensitive() != null) {
            boolean detected = SensitiveTopics.isSensitive(m.getContent());
            m.setSensitivity(request.sensitive() || detected ? Sensitivity.SENSITIVE : Sensitivity.NORMAL);
        }
        if (request.pinned() != null) {
            m.setPinned(request.pinned());
        }
        // Expiry follows category and pin state, and an edit renews a fading memory.
        m.setExpiresAt(MemoryPolicy.defaultExpiry(m.getCategory(), m.isPinned(), now, props.emotionalContextDays()));
        if (m.getStatus() == Status.EXPIRED) {
            m.setStatus(Status.ACTIVE);
        }
        m.setUpdatedAt(now);
        return MemoryResponse.from(repository.save(m), now);
    }

    /** The user confirms an inferred observation: it becomes a verified, fully trusted memory. */
    public MemoryResponse confirm(String userId, String id) {
        MemoryDocument m = find(userId, id);
        if (m.getStatus() != Status.ACTIVE) {
            throw ApiException.conflict("MEMORY_NOT_ACTIVE", "This memory was replaced or expired.");
        }
        Instant now = now();
        m.setUserVerified(true);
        m.setConfidence(1.0);
        m.setUpdatedAt(now);
        return MemoryResponse.from(repository.save(m), now);
    }

    /** Hard delete (PRD 12: user-initiated deletion is never soft). */
    public void delete(String userId, String id) {
        MemoryDocument m = find(userId, id);
        repository.delete(m);
    }

    /** Clears every memory of the user. @return how many were removed */
    public long clear(String userId) {
        long n = repository.findByUserIdOrderByUpdatedAtDesc(userId).size();
        repository.deleteByUserId(userId);
        return n;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rebuild
    // ---------------------------------------------------------------------------------------------------------

    /** Queues a rebuild from the user's conversations. Idempotent while one is queued or running. */
    public JobResponse rebuild(String userId) {
        UserDocument user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
        if (!user.isMemoryEnabled()) {
            throw ApiException.conflict("MEMORY_PAUSED", "Memory is paused. Turn it on to rebuild from your chats.");
        }
        if (!llm.isConfigured()) {
            throw ApiException.conflict("LLM_UNAVAILABLE", "Rebuilding needs the language model, which isn't available right now.");
        }
        JobQueueDocument job = jobs.enqueue(
                JobQueueDocument.Type.MEMORY_REBUILD, userId, Map.of(), "memory-rebuild:" + userId);
        return JobResponse.from(job);
    }

    public JobResponse job(String userId, String jobId) {
        return jobs.find(userId, jobId)
                .map(JobResponse::from)
                .orElseThrow(() -> ApiException.notFound("JOB_NOT_FOUND", "Job not found"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Maintenance
    // ---------------------------------------------------------------------------------------------------------

    /** Flips overdue memories to EXPIRED. Retrieval already ignores them; this keeps stored status honest. */
    public int expireDue(Instant now) {
        List<MemoryDocument> due = repository.findByStatusAndExpiresAtLessThan(Status.ACTIVE, now);
        int n = 0;
        for (MemoryDocument m : due) {
            if (m.isPinned()) {
                continue;
            }
            m.setStatus(Status.EXPIRED);
            m.setUpdatedAt(now);
            repository.save(m);
            n++;
        }
        return n;
    }

    static Set<String> parseIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String s = part.strip();
            if (!s.isEmpty() && s.length() <= 40 && s.matches("[A-Za-z0-9]+")) {
                out.add(s);
            }
            if (out.size() >= 50) {
                break;
            }
        }
        return out;
    }

    private MemoryDocument find(String userId, String id) {
        return repository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("MEMORY_NOT_FOUND", "Memory not found"));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
