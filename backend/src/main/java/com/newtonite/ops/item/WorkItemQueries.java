package com.newtonite.ops.item;

import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.common.Cursor;
import com.newtonite.ops.common.PageResponse;
import com.newtonite.ops.identity.CurrentUser;
import com.newtonite.ops.item.ItemDtos.DashboardSummary;
import com.newtonite.ops.item.ItemDtos.ItemFilter;
import com.newtonite.ops.item.ItemDtos.UserRef;
import com.newtonite.ops.item.ItemDtos.WorkItemSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read side of work items, written in HQL (Hibernate's JPQL). Every query is scoped to the caller's teams,
 * filtered and paginated in the database, so neither the browser nor the server ever holds the full dataset.
 * Results are projections (only the needed columns, as a {@link Tuple}), not managed entities.
 */
@Repository
@Transactional(readOnly = true)
public class WorkItemQueries {

    public static final int DEFAULT_LIMIT = 25;
    private static final Pattern ID_QUERY = Pattern.compile("#?(\\d{1,18})");
    private static final List<Status> FINISHED = List.of(Status.RESOLVED, Status.CLOSED);
    private static final List<Priority> URGENT = List.of(Priority.HIGH, Priority.URGENT);

    private static final String SELECT = """
            SELECT w.id AS id, w.teamId AS teamId, t.name AS teamName, w.title AS title, w.type AS type,
                   w.priority AS priority, w.status AS status, w.assigneeId AS assigneeId,
                   a.displayName AS assigneeName, w.dueAt AS dueAt, w.version AS version,
                   w.createdAt AS createdAt, w.updatedAt AS updatedAt
            """;
    // Entity joins on ids (WorkItem stores teamId/assigneeId, not associations), so no lazy loading.
    private static final String FROM = """
            FROM WorkItem w
            JOIN Team t ON t.id = w.teamId
            LEFT JOIN User a ON a.id = w.assigneeId
            """;

    private final EntityManager em;

    public WorkItemQueries(EntityManager em) {
        this.em = em;
    }

    public PageResponse<WorkItemSummary> list(CurrentUser user, ItemFilter filter) {
        int limit = filter.limit() == null ? DEFAULT_LIMIT : filter.limit();
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (!scopeToVisibleTeams(user, where, params)) {
            return new PageResponse<>(List.of(), null);
        }
        if (!applyView(user, filter.view(), where, params)) {
            return new PageResponse<>(List.of(), null);
        }
        if (filter.teamId() != null) {
            where.add("w.teamId = :teamId");
            params.put("teamId", filter.teamId());
        }
        addIn(where, params, "w.status", "statuses", filter.status());
        addIn(where, params, "w.priority", "priorities", filter.priority());
        addIn(where, params, "w.type", "types", filter.type());
        applyAssignee(user, filter.assignee(), where, params);
        applySearch(filter.q(), where, params);

        Cursor cursor = Cursor.decode(filter.cursor());
        if (cursor != null) {
            // Keyset pagination: rows strictly after the last row of the previous page.
            where.add("(w.updatedAt, w.id) < (:cAt, :cId)");
            params.put("cAt", cursor.at());
            params.put("cId", cursor.id());
        }

        TypedQuery<Tuple> query = em.createQuery(
                SELECT + FROM + whereClause(where) + " ORDER BY w.updatedAt DESC, w.id DESC", Tuple.class);
        params.forEach(query::setParameter);
        query.setMaxResults(limit + 1);   // one extra row tells us whether there is a next page
        List<WorkItemSummary> rows = query.getResultList().stream().map(WorkItemQueries::summary).toList();

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
        Map<String, Object> params = new HashMap<>();
        if (!scopeToVisibleTeams(user, where, params)) {
            return new DashboardSummary(0, 0, 0, 0);
        }
        String approvalScope;
        if (user.admin()) {
            approvalScope = "1 = 1";
        } else if (user.leadTeamIds().isEmpty()) {
            approvalScope = "1 = 0";
        } else {
            approvalScope = "w.teamId IN (:leadTeams)";
            params.put("leadTeams", user.leadTeamIds());
        }
        params.put("me", user.id());
        params.put("finished", FINISHED);
        params.put("open", Status.OPEN);
        params.put("urgent", URGENT);
        params.put("awaiting", Status.AWAITING_APPROVAL);
        params.put("now", Instant.now());

        TypedQuery<Tuple> query = em.createQuery("""
                SELECT
                  sum(CASE WHEN w.assigneeId = :me AND w.status NOT IN (:finished) THEN 1 ELSE 0 END) AS mine,
                  sum(CASE WHEN w.assigneeId IS NULL AND w.status = :open
                                AND w.priority IN (:urgent) THEN 1 ELSE 0 END) AS unassignedUrgent,
                  sum(CASE WHEN w.status = :awaiting AND %s THEN 1 ELSE 0 END) AS awaiting,
                  sum(CASE WHEN w.dueAt < :now AND w.status NOT IN (:finished) THEN 1 ELSE 0 END) AS overdue
                FROM WorkItem w
                """.formatted(approvalScope) + whereClause(where), Tuple.class);
        params.forEach(query::setParameter);
        Tuple t = query.getSingleResult();
        return new DashboardSummary(count(t, "mine"), count(t, "unassignedUrgent"), count(t, "awaiting"),
                count(t, "overdue"));
    }

    /** Detail row without permissions; the caller must already have checked visibility. */
    public Optional<DetailRow> detail(long id) {
        return em.createQuery(SELECT + ", w.description AS description, w.createdBy AS createdBy, "
                        + "c.displayName AS creatorName " + FROM + " JOIN User c ON c.id = w.createdBy WHERE w.id = :id",
                        Tuple.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .map(t -> new DetailRow(summary(t), t.get("description", String.class),
                        new UserRef(t.get("createdBy", Long.class), t.get("creatorName", String.class))));
    }

    public record DetailRow(WorkItemSummary summary, String description, UserRef createdBy) {
    }

    // ---------------------------------------------------------------- helpers

    /** Adds the team-visibility condition. Returns false if the user can see no team at all. */
    private static boolean scopeToVisibleTeams(CurrentUser user, List<String> where, Map<String, Object> params) {
        if (user.admin()) {
            return true;
        }
        Set<Long> teams = user.teamIds();
        if (teams.isEmpty()) {
            return false;
        }
        where.add("w.teamId IN (:visibleTeams)");
        params.put("visibleTeams", teams);
        return true;
    }

    /** Dashboard views. Returns false when the view is empty by definition for this user. */
    private static boolean applyView(CurrentUser user, String view, List<String> where, Map<String, Object> params) {
        if (view == null || view.isBlank() || view.equals("all")) {
            return true;
        }
        switch (view) {
            case "mine" -> {
                where.add("w.assigneeId = :me AND w.status NOT IN (:finished)");
                params.put("me", user.id());
                params.put("finished", FINISHED);
            }
            case "unassigned_urgent" -> {
                where.add("w.assigneeId IS NULL AND w.status = :open AND w.priority IN (:urgent)");
                params.put("open", Status.OPEN);
                params.put("urgent", URGENT);
            }
            case "awaiting_my_approval" -> {
                where.add("w.status = :awaiting");
                params.put("awaiting", Status.AWAITING_APPROVAL);
                if (!user.admin()) {
                    if (user.leadTeamIds().isEmpty()) {
                        return false;
                    }
                    where.add("w.teamId IN (:leadTeams)");
                    params.put("leadTeams", user.leadTeamIds());
                }
            }
            case "overdue" -> {
                where.add("w.dueAt < :now AND w.status NOT IN (:finished)");
                params.put("now", Instant.now());
                params.put("finished", FINISHED);
            }
            default -> throw ApiException.badRequest("INVALID_VIEW",
                    "view must be one of: all, mine, unassigned_urgent, awaiting_my_approval, overdue");
        }
        return true;
    }

    private static void applyAssignee(CurrentUser user, String assignee, List<String> where, Map<String, Object> params) {
        if (assignee == null || assignee.isBlank()) {
            return;
        }
        switch (assignee) {
            case "me" -> {
                where.add("w.assigneeId = :me");
                params.put("me", user.id());
            }
            case "none" -> where.add("w.assigneeId IS NULL");
            default -> {
                try {
                    params.put("assigneeId", Long.parseLong(assignee));
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("INVALID_ASSIGNEE_FILTER", "assignee must be 'me', 'none' or a user id");
                }
                where.add("w.assigneeId = :assigneeId");
            }
        }
    }

    /**
     * Full-text search over title and description; "#123" or "123" also matches the item id.
     * {@code fts} is a custom HQL function (see PostgresFunctions) that uses the GIN index.
     */
    private static void applySearch(String q, List<String> where, Map<String, Object> params) {
        if (q == null || q.isBlank()) {
            return;
        }
        String query = q.trim();
        params.put("q", query);
        var idMatch = ID_QUERY.matcher(query);
        if (idMatch.matches()) {
            params.put("qId", Long.parseLong(idMatch.group(1)));
            where.add("(w.id = :qId OR fts(w.searchVector, :q) = true)");
        } else {
            where.add("fts(w.searchVector, :q) = true");
        }
    }

    private static void addIn(List<String> where, Map<String, Object> params, String field, String name,
                              List<?> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        where.add(field + " IN (:" + name + ")");
        params.put(name, values);
    }

    private static String whereClause(List<String> where) {
        return where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
    }

    private static long count(Tuple t, String alias) {
        Number value = t.get(alias, Number.class);
        return value == null ? 0 : value.longValue();   // sum() over zero rows is null
    }

    private static WorkItemSummary summary(Tuple t) {
        Long assigneeId = t.get("assigneeId", Long.class);
        Status status = t.get("status", Status.class);
        Instant dueAt = t.get("dueAt", Instant.class);
        boolean overdue = dueAt != null && dueAt.isBefore(Instant.now()) && !status.isFinished();
        return new WorkItemSummary(
                t.get("id", Long.class), t.get("teamId", Long.class), t.get("teamName", String.class),
                t.get("title", String.class), t.get("type", ItemType.class), t.get("priority", Priority.class), status,
                assigneeId == null ? null : new UserRef(assigneeId, t.get("assigneeName", String.class)),
                dueAt, overdue, t.get("version", Long.class), t.get("createdAt", Instant.class),
                t.get("updatedAt", Instant.class));
    }
}
