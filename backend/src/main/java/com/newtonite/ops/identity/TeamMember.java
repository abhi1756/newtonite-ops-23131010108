package com.newtonite.ops.identity;

/** A member of a team, for the "assign to" picker. */
public record TeamMember(long id, String name, TeamRole role) {
}
