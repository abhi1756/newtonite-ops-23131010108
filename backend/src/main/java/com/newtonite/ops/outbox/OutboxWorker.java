package com.newtonite.ops.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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
 *   <li><b>Several workers are safe:</b> {@code FOR UPDATE SKIP LOCKED} gives each job to exactly one
 *       worker at a time; others skip it instead of waiting.</li>
 *   <li><b>Failures:</b> recorded in a separate transaction with exponential backoff; after
 *       {@code max_attempts} the job is parked as FAILED for a human to inspect, so a poison job
 *       cannot block the queue.</li>
 * </ul>
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Json json;
    private final Map<String, JobHandler> handlers;
    private final boolean enabled;
    private final int batchSize;

    public OutboxWorker(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Json json, List<JobHandler> handlers,
                        @Value("${app.outbox.enabled}") boolean enabled,
                        @Value("${app.outbox.batch-size}") int batchSize) {
        this.jdbc = jdbc;
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
                Job job = lockNextDueJob();
                if (job == null) {
                    return false;
                }
                JobHandler handler = handlers.get(job.type());
                try {
                    if (handler == null) {
                        throw new IllegalStateException("No handler for job type " + job.type());
                    }
                    handler.handle(job.id(), job.payload());
                } catch (RuntimeException e) {
                    throw new JobFailedException(job, handler == null, e);   // rolls back the handler's writes
                }
                jdbc.update("""
                        UPDATE outbox_jobs SET status = 'DONE', attempts = attempts + 1, last_error = NULL, updated_at = now()
                        WHERE id = :id
                        """, new MapSqlParameterSource("id", job.id()));
                return true;
            });
            return Boolean.TRUE.equals(found);
        } catch (JobFailedException e) {
            recordFailure(e);
            return true;
        }
    }

    private Job lockNextDueJob() {
        List<Job> jobs = jdbc.query("""
                SELECT id, type, payload::text AS payload, attempts, max_attempts
                FROM outbox_jobs
                WHERE status = 'PENDING' AND run_after <= now()
                ORDER BY run_after, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource(), (rs, i) -> new Job(rs.getLong("id"), rs.getString("type"),
                json.read(rs.getString("payload")), rs.getInt("attempts"), rs.getInt("max_attempts")));
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    private void recordFailure(JobFailedException e) {
        Job job = e.job;
        int attempts = job.attempts() + 1;
        boolean giveUp = e.permanent || attempts >= job.maxAttempts();
        Duration backoff = backoff(attempts);
        String error = e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage();
        tx.executeWithoutResult(status -> jdbc.update("""
                UPDATE outbox_jobs
                   SET attempts = :attempts,
                       status = :status,
                       last_error = left(:error, 2000),
                       run_after = now() + make_interval(secs => :backoffSeconds),
                       updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource("id", job.id())
                .addValue("attempts", attempts)
                .addValue("status", giveUp ? "FAILED" : "PENDING")
                .addValue("error", error)
                .addValue("backoffSeconds", (double) backoff.toSeconds())));
        if (giveUp) {
            log.error("Outbox job {} ({}) FAILED permanently after {} attempts: {}", job.id(), job.type(), attempts, error);
        } else {
            log.warn("Outbox job {} ({}) failed attempt {}/{}, retrying in {}s: {}", job.id(), job.type(), attempts,
                    job.maxAttempts(), backoff.toSeconds(), error);
        }
    }

    /** 2s, 4s, 8s, ... capped at 5 minutes. */
    static Duration backoff(int attempts) {
        long seconds = (long) Math.pow(2, Math.min(attempts, 20));
        return seconds > MAX_BACKOFF.toSeconds() ? MAX_BACKOFF : Duration.ofSeconds(seconds);
    }

    record Job(long id, String type, JsonNode payload, int attempts, int maxAttempts) {
    }

    private static final class JobFailedException extends RuntimeException {
        final Job job;
        final boolean permanent;

        JobFailedException(Job job, boolean permanent, RuntimeException cause) {
            super(cause);
            this.job = job;
            this.permanent = permanent;
        }
    }
}
