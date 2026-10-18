package com.newtonite.ops.outbox;

import com.newtonite.ops.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Processes outbox jobs in the background.
 *
 * <ul>
 *   <li><b>One transaction per job:</b> lock the job, run the handler, mark it DONE, commit.
 *       If the process dies halfway, the transaction rolls back and the job is simply picked up again.</li>
 *   <li><b>Several workers are safe:</b> the job is locked with "skip locked" (see
 *       {@link OutboxJobRepository#lockDue}), so each job goes to exactly one worker at a time and others
 *       move on instead of waiting.</li>
 *   <li><b>Failures:</b> recorded in a separate transaction with exponential backoff; after
 *       {@code maxAttempts} the job is parked as FAILED for a human to inspect, so a poison job
 *       cannot block the queue.</li>
 * </ul>
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final OutboxJobRepository jobs;
    private final TransactionTemplate tx;
    private final Json json;
    private final Map<String, JobHandler> handlers;
    private final boolean enabled;
    private final int batchSize;

    public OutboxWorker(OutboxJobRepository jobs, TransactionTemplate tx, Json json, List<JobHandler> handlers,
                        @Value("${app.outbox.enabled}") boolean enabled,
                        @Value("${app.outbox.batch-size}") int batchSize) {
        this.jobs = jobs;
        this.tx = tx;
        this.json = json;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms}", initialDelayString = "5000")
    public void poll() {
        if (!enabled) {
            return;
        }
        for (int i = 0; i < batchSize; i++) {
            if (!processNext()) {
                return;
            }
        }
    }

    /** Processes the next due job, if any. Returns false when no job is due. */
    public boolean processNext() {
        try {
            Boolean found = tx.execute(status -> {
                List<OutboxJob> due = jobs.lockDue(OutboxJob.JobStatus.PENDING, Instant.now(), PageRequest.of(0, 1));
                if (due.isEmpty()) {
                    return false;
                }
                OutboxJob job = due.get(0);
                JobHandler handler = handlers.get(job.getType());
                try {
                    if (handler == null) {
                        throw new IllegalStateException("No handler for job type " + job.getType());
                    }
                    handler.handle(job.getId(), json.toTree(job.getPayload()));
                } catch (RuntimeException e) {
                    throw new JobFailedException(job, handler == null, e);   // rolls back the handler's writes
                }
                job.markDone();   // saved at commit by dirty checking
                return true;
            });
            return Boolean.TRUE.equals(found);
        } catch (JobFailedException e) {
            recordFailure(e);
            return true;
        }
    }

    private void recordFailure(JobFailedException e) {
        int attempts = e.attempts + 1;
        boolean giveUp = e.permanent || attempts >= e.maxAttempts;
        Duration backoff = backoff(attempts);
        String error = e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage();
        tx.executeWithoutResult(status -> jobs.findById(e.jobId)
                .ifPresent(job -> job.recordFailure(error, giveUp, Instant.now().plus(backoff))));
        if (giveUp) {
            log.error("Outbox job {} ({}) FAILED permanently after {} attempts: {}", e.jobId, e.type, attempts, error);
        } else {
            log.warn("Outbox job {} ({}) failed attempt {}/{}, retrying in {}s: {}", e.jobId, e.type, attempts,
                    e.maxAttempts, backoff.toSeconds(), error);
        }
    }

    /** 2s, 4s, 8s, ... capped at 5 minutes. */
    static Duration backoff(int attempts) {
        long seconds = (long) Math.pow(2, Math.min(attempts, 20));
        return seconds > MAX_BACKOFF.toSeconds() ? MAX_BACKOFF : Duration.ofSeconds(seconds);
    }

    /** Carries what we need to record the failure after the job's transaction has been rolled back. */
    private static final class JobFailedException extends RuntimeException {
        final long jobId;
        final String type;
        final int attempts;
        final int maxAttempts;
        final boolean permanent;

        JobFailedException(OutboxJob job, boolean permanent, RuntimeException cause) {
            super(cause);
            this.jobId = job.getId();
            this.type = job.getType();
            this.attempts = job.getAttempts();
            this.maxAttempts = job.getMaxAttempts();
            this.permanent = permanent;
        }
    }
}
