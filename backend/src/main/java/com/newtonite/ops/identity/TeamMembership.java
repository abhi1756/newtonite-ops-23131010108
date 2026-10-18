package com.newtonite.ops.identity;

/** A team as seen by one user: the team plus that user's role in it. */
public record TeamMembership(long id, String name, TeamRole role) {
}
