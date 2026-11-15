package com.bookbuddy.jobs;

/** Executes one kind of durable job. Implementations are Spring beans discovered by {@link JobWorker}. */
public interface JobHandler {

    JobQueueDocument.Type type();

    /**
     * @return a short human-readable result stored on the job (never user content)
     * @throws BudgetDeferredException when the LLM budget is too low right now: the job is retried later, free of charge
     * @throws PermanentJobException when retrying cannot help
     */
    String handle(JobQueueDocument job) throws Exception;

    /** Thrown when the LLM budget does not allow this priority at the moment. */
    class BudgetDeferredException extends RuntimeException {
        public BudgetDeferredException(String message) {
            super(message, null, false, false);
        }
    }

    /** Thrown when a job cannot succeed however often it is retried (e.g. the user turned memory off). */
    class PermanentJobException extends RuntimeException {
        public PermanentJobException(String message) {
            super(message, null, false, false);
        }
    }
}
