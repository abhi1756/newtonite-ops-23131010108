package com.newtonite.ops.item;

import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.common.Cursor;
import com.newtonite.ops.common.PageResponse;
import com.newtonite.ops.identity.CurrentUser;
import com.newtonite.ops.item.ItemDtos.DashboardSummary;
import com.newtonite.ops.item.ItemDtos.ItemFilter;
import com.newtonite.ops.item.ItemDtos.UserRef;
import com.newtonite.ops.item.ItemDtos.WorkItemSummary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read side of work items, in SQL. Every query is scoped to the caller's teams, filtered and paginated
 * in the database, so neither the browser nor the server ever holds the full dataset.
 */
@Repository
public class WorkItemQueries {

    public static final int DEFAULT_LIMIT = 25;
    private static final Pattern ID_QUERY = Pattern.compile("#?(\\d{1,18})");
    private static final String OPEN_STATES = "w.status NOT IN ('RESOLVED', 'CLOSED')";

    private static final String SELECT = """
            SELECT w.id, w.team_id, t.name AS team_name, w.title, w.type, w.priority, w.status,
                   w.assignee_id, a.display_name AS assignee_name, w.due_at, w.version, w.created_at, w.updated_at
            """;
    private static final String FROM = """
            FROM work_items w
            JOIN teams t ON t.id = w.team_id
            LEFT JOIN users a ON a.id = w.assignee_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public WorkItemQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<WorkItemSummary> list(CurrentUser user, ItemFilter filter) {
        int limit = filter.limit() == null ? DEFAULT_LIMIT : filter.limit();
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource("me", user.id());

        if (!scopeToVisibleTeams(user, where, params)) {
            return new PageResponse<>(List.of(), null);
        }
        if (!applyView(user, filter.view(), where, params)) {
            return new PageResponse<>(List.of(), null);
        }
        if (filter.teamId() != null) {
            where.add("w.team_id = :teamId");
            params.addValue("teamId", filter.teamId());
        }
        addIn(where, params, "w.status", "statuses", filter.status());
        addIn(where, params, "w.priority", "priorities", filter.priority());
        addIn(where, params, "w.type", "types", filter.type());
        applyAssignee(filter.assignee(), where, params);
        applySearch(filter.q(), where, params);

        Cursor cursor = Cursor.decode(filter.cursor());
        if (cursor != null) {
            where.add("(w.updated_at, w.id) < (:cAt, :cId)");
            params.addValue("cAt", cursor.atUtc()).addValue("cId", cursor.id());
        }
        params.addValue("limit", limit + 1);   // one extra row tells us whether there is a next page

        String sql = SELECT + FROM + whereClause(where) + " ORDER BY w.updated_at DESC, w.id DESC LIMIT :limit";
        List<WorkItemSummary> rows = jdbc.query(sql, params, (rs, i) -> summary(rs));

        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            WorkItemSummary last = rows.get(limit - 1);
            next = Cursor.encode(last.updatedAt(), last.id());
        }
        return new PageResponse<>(rows, next);
    }

    /** Counts for the dashboard tiles, computed in one pass over the caller's teams. */
    public DashboardSummary summary(CurrentUser user) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource("me", user.id());
        if (!scopeToVisibleTeams(user, where, params)) {
            return new DashboardSummary(0, 0, 0, 0);
        }
        String approvalScope;
        if (user.admin()) {
            approvalScope = "TRUE";
        } else if (user.leadTeamIds().isEmpty()) {
            approvalScope = "FALSE";
        } else {
            approvalScope = "w.team_id IN (:leadTeams)";
            params.addValue("leadTeams", user.leadTeamIds());
        }
        String sql = """
                SELECT
                  count(*) FILTER (WHERE w.assignee_id = :me AND %1$s) AS mine,
                  count(*) FILTER (WHERE w.assignee_id IS NULL AND w.status = 'OPEN'
                                     AND w.priority IN ('HIGH', 'URGENT')) AS unassigned_urgent,
                  count(*) FILTER (WHERE w.status = 'AWAITING_APPROVAL' AND %2$s) AS awaiting,
                  count(*) FILTER (WHERE w.due_at < now() AND %1$s) AS overdue
                FROM work_items w
                """.formatted(OPEN_STATES, approvalScope) + whereClause(where);
        return jdbc.queryForObject(sql, params, (rs, i) -> new DashboardSummary(
                rs.getLong("mine"), rs.getLong("unassigned_urgent"), rs.getLong("awaiting"), rs.getLong("overdue")));
    }

    /** Detail row without permissions; the caller must already have checked visibility. */
    public Optional<DetailRow> detail(long id) {
        String sql = SELECT + ", w.description, w.created_by, c.display_name AS creator_name " + FROM
                + " JOIN users c ON c.id = w.created_by WHERE w.id = :id";
        return jdbc.query(sql, new MapSqlParameterSource("id", id), (rs, i) -> new DetailRow(
                summary(rs), rs.getString("description"),
                new UserRef(rs.getLong("created_by"), rs.getString("creator_name")))).stream().findFirst();
    }

    public record DetailRow(WorkItemSummary summary, String description, UserRef createdBy) {
    }

    // ---------------------------------------------------------------- helpers

    /** Adds the team-visibility condition. Returns false if the user can see no team at all. */
    private static boolean scopeToVisibleTeams(CurrentUser user, List<String> where, MapSqlParameterSource params) {
        if (user.admin()) {
            return true;
        }
        Set<Long> teams = user.teamIds();
        if (teams.isEmpty()) {
            return false;
        }
        where.add("w.team_id IN (:visibleTeams)");
        params.addValue("visibleTeams", teams);
        return true;
    }

    /** Dashboard views. Returns false when the view is empty by definition for this user. */
    private static boolean applyView(CurrentUser user, String view, List<String> where, MapSqlParameterSource params) {
        if (view == null || view.isBlank() || view.equals("all")) {
            return true;
        }
        switch (view) {
            case "mine" -> where.add("w.assignee_id = :me AND " + OPEN_STATES);
            case "unassigned_urgent" ->
                    where.add("w.assignee_id IS NULL AND w.status = 'OPEN' AND w.priority IN ('HIGH', 'URGENT')");
            case "awaiting_my_approval" -> {
                where.add("w.status = 'AWAITING_APPROVAL'");
                if (!user.admin()) {
                    if (user.leadTeamIds().isEmpty()) {
                        return false;
                    }
                    where.add("w.team_id IN (:leadTeams)");
                    params.addValue("leadTeams", user.leadTeamIds());
                }
            }
            case "overdue" -> where.add("w.due_at < now() AND " + OPEN_STATES);
            default -> throw ApiException.badRequest("INVALID_VIEW",
                    "view must be one of: all, mine, unassigned_urgent, awaiting_my_approval, overdue");
        }
        return true;
    }

    private static void applyAssignee(String assignee, List<String> where, MapSqlParameterSource params) {
        if (assignee == null || assignee.isBlank()) {
            return;
        }
        switch (assignee) {
            case "me" -> where.add("w.assignee_id = :me");
            case "none" -> where.add("w.assignee_id IS NULL");
            default -> {
                try {
                    params.addValue("assigneeId", Long.parseLong(assignee));
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("INVALID_ASSIGNEE_FILTER", "assignee must be 'me', 'none' or a user id");
                }
                where.add("w.assignee_id = :assigneeId");
            }
        }
    }

    /** Full-text search over title and description; "#123" or "123" also matches the item id. */
    private static void applySearch(String q, List<String> where, MapSqlParameterSource params) {
        if (q == null || q.isBlank()) {
            return;
        }
        String query = q.trim();
        params.addValue("q", query);
        var idMatch = ID_QUERY.matcher(query);
        if (idMatch.matches()) {
            params.addValue("qId", Long.parseLong(idMatch.group(1)));
            where.add("(w.id = :qId OR w.search_vector @@ websearch_to_tsquery('english', :q))");
        } else {
            where.add("w.search_vector @@ websearch_to_tsquery('english', :q)");
        }
    }

    private static void addIn(List<String> where, MapSqlParameterSource params, String column, String name,
                              Collection<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        where.add(column + " IN (:" + name + ")");
        params.addValue(name, values.stream().map(Enum::name).toList());
    }

    private static String whereClause(List<String> where) {
        return where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
    }

    private static WorkItemSummary summary(ResultSet rs) throws SQLException {
        Long assigneeId = rs.getObject("assignee_id", Long.class);
        Status status = Status.valueOf(rs.getString("status"));
        Instant dueAt = instant(rs, "due_at");
        boolean overdue = dueAt != null && dueAt.isBefore(Instant.now()) && !status.isFinished();
        return new WorkItemSummary(
                rs.getLong("id"), rs.getLong("team_id"), rs.getString("team_name"), rs.getString("title"),
                ItemType.valueOf(rs.getString("type")), Priority.valueOf(rs.getString("priority")), status,
                assigneeId == null ? null : new UserRef(assigneeId, rs.getString("assignee_name")),
                dueAt, overdue, rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
