package com.newtonite.ops.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** A unit of asynchronous work, inserted in the same transaction as the change that caused it. */
@Entity
@Table(name = "outbox_jobs")
public class OutboxJob {

    public enum JobStatus { PENDING, DONE, FAILED }

    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "run_after", nullable = false)
    private Instant runAfter;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxJob() {
    }

    public OutboxJob(String type, Map<String, Object> payload, int maxAttempts) {
        this.type = type;
        this.payload = payload;
        this.maxAttempts = maxAttempts;
        this.status = JobStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        if (runAfter == null) {
            runAfter = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void markDone() {
        attempts++;
        status = JobStatus.DONE;
        lastError = null;
    }

    /** Records a failed attempt: retry at {@code nextRun}, or park the job as FAILED. */
    public void recordFailure(String error, boolean giveUp, Instant nextRun) {
        attempts++;
        status = giveUp ? JobStatus.FAILED : JobStatus.PENDING;
        lastError = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        runAfter = nextRun;
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getRunAfter() {
        return runAfter;
    }

    public void setRunAfter(Instant runAfter) {
        this.runAfter = runAfter;
    }

    public String getLastError() {
        return lastError;
    }
}
