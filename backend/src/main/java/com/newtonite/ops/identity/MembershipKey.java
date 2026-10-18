package com.newtonite.ops.identity;

import java.io.Serializable;
import java.util.Objects;

/** Composite primary key of {@link Membership}: JPA needs a class with the same id fields. */
public class MembershipKey implements Serializable {

    private Long userId;
    private Long teamId;

    public MembershipKey() {
    }

    public MembershipKey(Long userId, Long teamId) {
        this.userId = userId;
        this.teamId = teamId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MembershipKey k && Objects.equals(userId, k.userId) && Objects.equals(teamId, k.teamId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, teamId);
    }
}
