package com.bookbuddy.jobs;

import com.bookbuddy.jobs.JobHandler.BudgetDeferredException;
import com.bookbuddy.jobs.JobHandler.PermanentJobException;
import com.bookbuddy.jobs.JobQueueDocument.Type;
import com.bookbuddy.quota.LlmBudgetService;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@code jobs_queue} and runs due jobs one at a time. A job is deferred (not failed) while the LLM budget is
 * too low for its priority. Disable with {@code bookbuddy.jobs.enabled=false}.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    static final int MAX_JOBS_PER_POLL = 3;

    private final JobQueueService queue;
    private final LlmBudgetService budget;
    private final Map<Type, JobHandler> handlers = new EnumMap<>(Type.class);
    private final boolean enabled;
    private final AtomicBoolean polling = new AtomicBoolean();

    public JobWorker(
            JobQueueService queue,
            LlmBudgetService budget,
            List<JobHandler> handlerBeans,
            @Value("${bookbuddy.jobs.enabled:true}") boolean enabled) {
        this.queue = queue;
        this.budget = budget;
        this.enabled = enabled;
        for (JobHandler h : handlerBeans) {
            handlers.put(h.type(), h);
        }
    }

    @Scheduled(initialDelayString = "${bookbuddy.jobs.initial-delay-ms:15000}",
            fixedDelayString = "${bookbuddy.jobs.poll-ms:5000}")
    void scheduledPoll() {
        if (enabled) {
            poll();
        }
    }

    /** @return the number of jobs that were claimed (whatever their outcome) */
    public int poll() {
        if (!polling.compareAndSet(false, true)) {
            return 0;
        }
        int claimed = 0;
        try {
            for (int i = 0; i < MAX_JOBS_PER_POLL; i++) {
                Optional<JobQueueDocument> next = queue.claimNext();
                if (next.isEmpty()) {
                    break;
                }
                claimed++;
                runOne(next.get());
            }
        } catch (RuntimeException e) {
            log.warn("Job poll failed: {}", e.getClass().getSimpleName());
        } finally {
            polling.set(false);
        }
        return claimed;
    }

    void runOne(JobQueueDocument job) {
        JobHandler handler = handlers.get(job.getType());
        if (handler == null) {
            queue.fail(job, "No handler for " + job.getType(), false);
            return;
        }
        if (!budget.allows(job.getType().priority())) {
            queue.release(job, budget.retryAt());
            return;
        }
        try {
            String result = handler.handle(job);
            queue.complete(job, result);
        } catch (BudgetDeferredException e) {
            queue.release(job, budget.retryAt());
        } catch (PermanentJobException e) {
            queue.fail(job, e.getMessage(), false);
        } catch (Exception e) {
            log.warn("Job {} ({}) failed: {}", job.getId(), job.getType(), e.getClass().getSimpleName());
            queue.fail(job, e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }
}
