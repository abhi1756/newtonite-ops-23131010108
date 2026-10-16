package com.newtonite.ops.identity;

/** Role of a user inside one team. Declaration order is the privilege order. */
public enum TeamRole {
    VIEWER,
    MEMBER,
    LEAD;

    public boolean atLeast(TeamRole other) {
        return compareTo(other) >= 0;
    }
}
