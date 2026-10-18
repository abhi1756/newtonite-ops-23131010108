package com.newtonite.ops.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxJobRepository extends JpaRepository<OutboxJob, Long> {

    /**
     * Locks the next due job(s) for this worker. PESSIMISTIC_WRITE adds a row lock; the lock timeout
     * hint -2 is Hibernate's "SKIP LOCKED", so a job already locked by another worker is skipped
     * instead of waited for. On PostgreSQL this renders as {@code ... FOR NO KEY UPDATE SKIP LOCKED}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT j FROM OutboxJob j
            WHERE j.status = :status AND j.runAfter <= :now
            ORDER BY j.runAfter, j.id
            """)
    List<OutboxJob> lockDue(@Param("status") OutboxJob.JobStatus status, @Param("now") Instant now, Pageable page);

    List<OutboxJob> findByType(String type);
}
