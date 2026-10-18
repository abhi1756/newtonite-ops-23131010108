package com.newtonite.ops.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MembershipRepository extends JpaRepository<Membership, MembershipKey> {

    List<Membership> findByUserId(Long userId);

    Optional<Membership> findByUserIdAndTeamId(Long userId, Long teamId);

    /** Users with a given role in a team, except one (used to notify approvers other than the requester). */
    @Query("""
            SELECT m.userId FROM Membership m
            WHERE m.teamId = :teamId AND m.role = :role AND m.userId <> :excludeUserId
            """)
    List<Long> findUserIdsWithRole(@Param("teamId") Long teamId, @Param("role") TeamRole role,
                                   @Param("excludeUserId") Long excludeUserId);
}
