package com.newtonite.ops.identity;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The authenticated user for one request. Roles are loaded from the database on every request
 * (not baked into the JWT), so a role change takes effect immediately.
 */
public record CurrentUser(long id, String email, String displayName, boolean admin, Map<Long, TeamRole> roles) {

    public Set<Long> teamIds() {
        return roles.keySet();
    }

    public Set<Long> leadTeamIds() {
        return roles.entrySet().stream()
                .filter(e -> e.getValue() == TeamRole.LEAD)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
