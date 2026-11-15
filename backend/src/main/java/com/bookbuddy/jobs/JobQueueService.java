package com.bookbuddy.jobs;

import com.bookbuddy.jobs.JobQueueDocument.Status;
import com.bookbuddy.jobs.JobQueueDocument.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Durable queue operations on {@code jobs_queue}. Claiming is atomic, so several workers never run the same job. */
@Service
public class JobQueueService {

    /** How long a claimed job may run before it is considered abandoned. */
    public static final Duration LOCK_FOR = Duration.ofMinutes(10);

    private static final EnumSet<Status> UNFINISHED = EnumSet.of(Status.PENDING, Status.RUNNING);

    private final JobQueueRepository repository;
    private final MongoTemplate mongo;
    private final Clock clock;

    @Autowired
    public JobQueueService(JobQueueRepository repository, MongoTemplate mongo) {
        this(repository, mongo, Clock.systemUTC());
    }

    JobQueueService(JobQueueRepository repository, MongoTemplate mongo, Clock clock) {
        this.repository = repository;
        this.mongo = mongo;
        this.clock = clock;
    }

    /**
     * Like {@link #enqueue} but a job with this key in <em>any</em> state (also DONE / FAILED) counts as the same job,
     * so identical work is never scheduled twice, and a job that kept failing is not retried forever.
     */
    public JobQueueDocument enqueueOnce(Type type, String userId, Map<String, String> payload, String dedupeKey) {
        Optional<JobQueueDocument> existing =
                repository.findFirstByDedupeKeyAndStatusIn(dedupeKey, EnumSet.allOf(Status.class));
        return existing.orElseGet(() -> create(type, userId, payload, dedupeKey, null));
    }

    /** Enqueues a job, or returns the unfinished one that already has the same {@code dedupeKey}. */
    public JobQueueDocument enqueue(Type type, String userId, Map<String, String> payload, String dedupeKey) {
        return enqueue(type, userId, payload, dedupeKey, null);
    }

    public JobQueueDocument enqueue(
            Type type, String userId, Map<String, String> payload, String dedupeKey, Instant notBefore) {
        if (dedupeKey != null) {
            Optional<JobQueueDocument> existing = repository.findFirstByDedupeKeyAndStatusIn(dedupeKey, UNFINISHED);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return create(type, userId, payload, dedupeKey, notBefore);
    }

    private JobQueueDocument create(
            Type type, String userId, Map<String, String> payload, String dedupeKey, Instant notBefore) {
        Instant now = now();
        JobQueueDocument job = new JobQueueDocument();
        job.setType(type);
        job.setUserId(userId);
        job.setPayload(payload == null ? new java.util.HashMap<>() : new java.util.HashMap<>(payload));
        job.setDedupeKey(dedupeKey);
        job.setPriority(type.priority());
        job.setPriorityRank(type.priority().ordinal());
        job.setStatus(Status.PENDING);
        job.setNotBefore(notBefore == null ? now : notBefore);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return repository.save(job);
    }

    /**
     * Atomically takes the most urgent due job (lowest priority ordinal, then oldest), or abandoned RUNNING jobs whose
     * lock expired. The attempt counter is incremented by the claim.
     */
    public Optional<JobQueueDocument> claimNext() {
        Instant now = now();
        Criteria due = new Criteria().orOperator(
                Criteria.where("status").is(Status.PENDING).and("notBefore").lte(now),
                Criteria.where("status").is(Status.RUNNING).and("lockedUntil").lt(now));
        Query query = Query.query(due).with(Sort.by(Sort.Order.asc("priorityRank"), Sort.Order.asc("createdAt")));
        Update update = new Update()
                .set("status", Status.RUNNING)
                .set("lockedUntil", now.plus(LOCK_FOR))
                .set("updatedAt", now)
                .inc("attempts", 1);
        JobQueueDocument job =
                mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), JobQueueDocument.class);
        return Optional.ofNullable(job);
    }

    public void complete(JobQueueDocument job, String result) {
        Instant now = now();
        job.setStatus(Status.DONE);
        job.setResult(result);
        job.setLastError(null);
        job.setLockedUntil(null);
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        repository.save(job);
    }

    /** Puts a claimed job back without counting the attempt (e.g. the LLM budget is low). */
    public void release(JobQueueDocument job, Instant notBefore) {
        job.setStatus(Status.PENDING);
        job.setAttempts(Math.max(0, job.getAttempts() - 1));
        job.setNotBefore(notBefore);
        job.setLockedUntil(null);
        job.setUpdatedAt(now());
        repository.save(job);
    }

    /** Retry with exponential backoff until {@code maxAttempts}, then FAILED. */
    public void fail(JobQueueDocument job, String error, boolean retryable) {
        Instant now = now();
        job.setLastError(error == null ? null : error.substring(0, Math.min(300, error.length())));
        job.setLockedUntil(null);
        job.setUpdatedAt(now);
        if (retryable && job.getAttempts() < job.getMaxAttempts()) {
            long minutes = 1L << Math.min(job.getAttempts(), 6);
            job.setStatus(Status.PENDING);
            job.setNotBefore(now.plus(Duration.ofMinutes(minutes)));
        } else {
            job.setStatus(Status.FAILED);
            job.setFinishedAt(now);
        }
        repository.save(job);
    }

    public Optional<JobQueueDocument> find(String userId, String jobId) {
        return repository.findByIdAndUserId(jobId, userId);
    }

    public long deleteForUser(String userId) {
        return repository.deleteByUserId(userId);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
