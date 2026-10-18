package com.newtonite.ops.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * A user's role in one team. The primary key is the (user, team) pair, so a user has at most one
 * role per team but can belong to several teams with different roles.
 */
@Entity
@Table(name = "memberships")
@IdClass(MembershipKey.class)
public class Membership {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "team_id")
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TeamRole role;

    protected Membership() {
    }

    public Membership(Long userId, Long teamId, TeamRole role) {
        this.userId = userId;
        this.teamId = teamId;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getTeamId() {
        return teamId;
    }

    public TeamRole getRole() {
        return role;
    }
}
