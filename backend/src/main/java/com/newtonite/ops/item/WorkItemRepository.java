package com.newtonite.ops.item;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;

public interface WorkItemRepository extends JpaRepository<WorkItem, Long> {

    /**
     * Atomic claim. The "assigneeId IS NULL" condition is evaluated by Postgres under the row lock,
     * so when several users claim at once exactly one UPDATE matches and the others affect 0 rows.
     * Bulk JPQL bypasses @Version handling, so the version is bumped explicitly.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE WorkItem w
               SET w.assigneeId = :userId, w.version = w.version + 1, w.updatedAt = :now
             WHERE w.id = :id AND w.assigneeId IS NULL AND w.status IN :claimable
            """)
    int claimIfUnassigned(@Param("id") long id, @Param("userId") long userId, @Param("now") Instant now,
                          @Param("claimable") Collection<Status> claimable);

    long countByTeamId(Long teamId);
}
