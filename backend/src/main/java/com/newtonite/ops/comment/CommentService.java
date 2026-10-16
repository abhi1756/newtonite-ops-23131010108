package com.newtonite.ops.comment;

import com.newtonite.ops.activity.ActivityService;
import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.common.Cursor;
import com.newtonite.ops.common.PageResponse;
import com.newtonite.ops.identity.CurrentUser;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.item.WorkItemService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static com.newtonite.ops.common.Json.obj;

/**
 * Comments are append-only. Adding one does not bump the item's version, so discussion never makes
 * someone else's pending edit "stale".
 */
@Service
public class CommentService {

    private final NamedParameterJdbcTemplate jdbc;
    private final WorkItemService items;
    private final ActivityService activity;

    public CommentService(NamedParameterJdbcTemplate jdbc, WorkItemService items, ActivityService activity) {
        this.jdbc = jdbc;
        this.items = items;
        this.activity = activity;
    }

    @Transactional
    public CommentEntry add(CurrentUser user, long itemId, String body) {
        WorkItemService.Loaded loaded = items.loadVisible(user, itemId);
        if (!loaded.role().atLeast(TeamRole.MEMBER)) {
            throw ApiException.forbidden("A " + loaded.role() + " of this team cannot comment");
        }
        String text = body.trim();
        CommentEntry entry = jdbc.queryForObject("""
                INSERT INTO comments (item_id, author_id, body) VALUES (:itemId, :authorId, :body)
                RETURNING id, created_at
                """, new MapSqlParameterSource("itemId", itemId).addValue("authorId", user.id()).addValue("body", text),
                (rs, i) -> new CommentEntry(rs.getLong("id"), text, user.id(), user.displayName(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()));
        activity.record(itemId, user.id(), "COMMENTED",
                obj("commentId", entry.id(), "excerpt", text.length() > 80 ? text.substring(0, 80) + "..." : text));
        return entry;
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentEntry> list(CurrentUser user, long itemId, String cursorValue, int limit) {
        items.loadVisible(user, itemId);
        Cursor cursor = Cursor.decode(cursorValue);
        MapSqlParameterSource params = new MapSqlParameterSource("itemId", itemId).addValue("limit", limit + 1);
        String cursorClause = "";
        if (cursor != null) {
            cursorClause = "AND (c.created_at, c.id) < (:cAt, :cId)";
            params.addValue("cAt", cursor.atUtc()).addValue("cId", cursor.id());
        }
        List<CommentEntry> rows = jdbc.query("""
                SELECT c.id, c.body, c.author_id, u.display_name, c.created_at
                FROM comments c JOIN users u ON u.id = c.author_id
                WHERE c.item_id = :itemId %s
                ORDER BY c.created_at DESC, c.id DESC
                LIMIT :limit
                """.formatted(cursorClause), params, (rs, i) -> new CommentEntry(rs.getLong("id"),
                rs.getString("body"), rs.getLong("author_id"), rs.getString("display_name"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            CommentEntry last = rows.get(limit - 1);
            next = Cursor.encode(last.createdAt(), last.id());
        }
        return new PageResponse<>(rows, next);
    }

    public record CommentEntry(long id, String body, long authorId, String authorName, Instant createdAt) {
    }
}
