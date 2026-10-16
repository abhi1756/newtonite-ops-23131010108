package com.newtonite.ops.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read access to users, teams and memberships. */
@Repository
public class UserDirectory {

    private static final RowMapper<UserAccount> USER = (rs, i) -> new UserAccount(
            rs.getLong("id"), rs.getString("email"), rs.getString("display_name"),
            rs.getString("password_hash"), rs.getBoolean("is_admin"));

    private final JdbcTemplate jdbc;

    public UserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.query("SELECT * FROM users WHERE lower(email) = lower(?)", USER, email.trim())
                .stream().findFirst();
    }

    public Optional<UserAccount> findById(long id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", USER, id).stream().findFirst();
    }

    public Map<Long, TeamRole> rolesOf(long userId) {
        Map<Long, TeamRole> roles = new HashMap<>();
        jdbc.query("SELECT team_id, role FROM memberships WHERE user_id = ?",
                rs -> {
                    roles.put(rs.getLong("team_id"), TeamRole.valueOf(rs.getString("role")));
                }, userId);
        return roles;
    }

    public Optional<TeamRole> roleOf(long userId, long teamId) {
        return jdbc.query("SELECT role FROM memberships WHERE user_id = ? AND team_id = ?",
                (rs, i) -> TeamRole.valueOf(rs.getString("role")), userId, teamId).stream().findFirst();
    }

    public CurrentUser loadCurrentUser(long userId) {
        return findById(userId)
                .map(u -> new CurrentUser(u.id(), u.email(), u.displayName(), u.admin(), rolesOf(u.id())))
                .orElse(null);
    }

    /** Teams visible to the user with the user's effective role (admins see every team as LEAD). */
    public List<TeamMembership> teamsOf(CurrentUser user) {
        if (user.admin()) {
            return jdbc.query("SELECT id, name FROM teams ORDER BY name",
                    (rs, i) -> new TeamMembership(rs.getLong("id"), rs.getString("name"), TeamRole.LEAD));
        }
        return jdbc.query("""
                SELECT t.id, t.name, m.role FROM memberships m JOIN teams t ON t.id = m.team_id
                WHERE m.user_id = ? ORDER BY t.name
                """, (rs, i) -> new TeamMembership(rs.getLong("id"), rs.getString("name"),
                TeamRole.valueOf(rs.getString("role"))), user.id());
    }

    public List<TeamMember> membersOf(long teamId) {
        return jdbc.query("""
                SELECT u.id, u.display_name, m.role FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.team_id = ? ORDER BY u.display_name
                """, (rs, i) -> new TeamMember(rs.getLong("id"), rs.getString("display_name"),
                TeamRole.valueOf(rs.getString("role"))), teamId);
    }

    public Optional<String> teamName(long teamId) {
        return jdbc.query("SELECT name FROM teams WHERE id = ?", (rs, i) -> rs.getString(1), teamId)
                .stream().findFirst();
    }

    public record TeamMembership(long id, String name, TeamRole role) {
    }

    public record TeamMember(long id, String name, TeamRole role) {
    }
}
