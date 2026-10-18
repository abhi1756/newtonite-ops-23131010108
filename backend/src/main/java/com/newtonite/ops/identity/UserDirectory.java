package com.newtonite.ops.identity;

import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read access to users, teams and memberships. */
@Service
@Transactional(readOnly = true)
public class UserDirectory {

    private final UserRepository users;
    private final TeamRepository teams;
    private final MembershipRepository memberships;
    private final EntityManager em;

    public UserDirectory(UserRepository users, TeamRepository teams, MembershipRepository memberships,
                         EntityManager em) {
        this.users = users;
        this.teams = teams;
        this.memberships = memberships;
        this.em = em;
    }

    public Optional<UserAccount> findByEmail(String email) {
        return users.findByEmailIgnoreCase(email.trim()).map(User::toAccount);
    }

    public Optional<UserAccount> findById(long id) {
        return users.findById(id).map(User::toAccount);
    }

    public Map<Long, TeamRole> rolesOf(long userId) {
        Map<Long, TeamRole> roles = new HashMap<>();
        memberships.findByUserId(userId).forEach(m -> roles.put(m.getTeamId(), m.getRole()));
        return roles;
    }

    public Optional<TeamRole> roleOf(long userId, long teamId) {
        return memberships.findByUserIdAndTeamId(userId, teamId).map(Membership::getRole);
    }

    public CurrentUser loadCurrentUser(long userId) {
        return users.findById(userId)
                .map(u -> new CurrentUser(u.getId(), u.getEmail(), u.getDisplayName(), u.isAdmin(), rolesOf(u.getId())))
                .orElse(null);
    }

    /** Teams visible to the user with the user's effective role (admins see every team as LEAD). */
    public List<TeamMembership> teamsOf(CurrentUser user) {
        if (user.admin()) {
            return teams.findAll(Sort.by("name")).stream()
                    .map(t -> new TeamMembership(t.getId(), t.getName(), TeamRole.LEAD))
                    .toList();
        }
        return em.createQuery("""
                        SELECT new com.newtonite.ops.identity.TeamMembership(t.id, t.name, m.role)
                        FROM Membership m JOIN Team t ON t.id = m.teamId
                        WHERE m.userId = :userId
                        ORDER BY t.name
                        """, TeamMembership.class)
                .setParameter("userId", user.id())
                .getResultList();
    }

    public List<TeamMember> membersOf(long teamId) {
        return em.createQuery("""
                        SELECT new com.newtonite.ops.identity.TeamMember(u.id, u.displayName, m.role)
                        FROM Membership m JOIN User u ON u.id = m.userId
                        WHERE m.teamId = :teamId
                        ORDER BY u.displayName
                        """, TeamMember.class)
                .setParameter("teamId", teamId)
                .getResultList();
    }
}
