package com.bookbuddy.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookbuddy.ai.LlmException;
import com.bookbuddy.jobs.JobHandler.BudgetDeferredException;
import com.bookbuddy.jobs.JobHandler.PermanentJobException;
import com.bookbuddy.jobs.JobQueueDocument.Type;
import com.bookbuddy.quota.LlmBudgetService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobWorkerTest {

    private static final Instant LATER = Instant.parse("2026-06-01T10:10:00Z");

    private final JobQueueService queue = mock(JobQueueService.class);
    private final LlmBudgetService budget = mock(LlmBudgetService.class);
    private final JobHandler extraction = mock(JobHandler.class);
    private JobWorker worker;

    @BeforeEach
    void setUp() {
        when(extraction.type()).thenReturn(Type.MEMORY_EXTRACTION);
        when(budget.allows(any())).thenReturn(true);
        when(budget.retryAt()).thenReturn(LATER);
        worker = new JobWorker(queue, budget, List.of(extraction), true);
    }

    private static JobQueueDocument job(Type type) {
        JobQueueDocument j = new JobQueueDocument();
        j.setId("j1");
        j.setType(type);
        j.setUserId("u1");
        return j;
    }

    @Test
    void completesAJobAndStoresTheResult() throws Exception {
        JobQueueDocument j = job(Type.MEMORY_EXTRACTION);
        when(extraction.handle(j)).thenReturn("added=1");

        worker.runOne(j);

        verify(queue).complete(j, "added=1");
    }

    @Test
    void aLowBudgetDefersTheJobWithoutRunningIt() throws Exception {
        JobQueueDocument j = job(Type.MEMORY_EXTRACTION);
        when(budget.allows(Type.MEMORY_EXTRACTION.priority())).thenReturn(false);

        worker.runOne(j);

        verify(extraction, never()).handle(any());
        verify(queue).release(j, LATER);
        verify(queue, never()).fail(any(), anyString(), anyBoolean());
    }

    @Test
    void aHandlerThatFindsTheBudgetLowDefersToo() throws Exception {
        JobQueueDocument j = job(Type.MEMORY_EXTRACTION);
        when(extraction.handle(j)).thenThrow(new BudgetDeferredException("low"));

        worker.runOne(j);

        verify(queue).release(j, LATER);
    }

    @Test
    void permanentFailuresAreNotRetried() throws Exception {
        JobQueueDocument j = job(Type.MEMORY_EXTRACTION);
        when(extraction.handle(j)).thenThrow(new PermanentJobException("bad payload"));

        worker.runOne(j);

        verify(queue).fail(j, "bad payload", false);
    }

    @Test
    void otherFailuresAreRetriedWithBackoff() throws Exception {
        JobQueueDocument j = job(Type.MEMORY_EXTRACTION);
        when(extraction.handle(j)).thenThrow(new LlmException("429"));

        worker.runOne(j);

        verify(queue).fail(eq(j), anyString(), eq(true));
        verify(queue, never()).complete(any(), anyString());
    }

    @Test
    void jobsWithoutAHandlerAreFailedForGood() {
        JobQueueDocument j = job(Type.MEMORY_REBUILD);

        worker.runOne(j);

        verify(queue).fail(eq(j), anyString(), eq(false));
    }

    @Test
    void pollHandlesAtMostThreeJobsAndStopsWhenTheQueueIsEmpty() {
        when(queue.claimNext()).thenReturn(Optional.of(job(Type.MEMORY_EXTRACTION)));
        assertEquals(JobWorker.MAX_JOBS_PER_POLL, worker.poll());

        org.mockito.Mockito.reset(queue);
        when(queue.claimNext()).thenReturn(Optional.of(job(Type.MEMORY_EXTRACTION)), Optional.empty());
        assertEquals(1, worker.poll());
    }
}
