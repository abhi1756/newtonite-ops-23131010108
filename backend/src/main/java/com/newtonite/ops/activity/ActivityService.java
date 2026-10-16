package com.newtonite.ops.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.common.Cursor;
import com.newtonite.ops.common.Json;
import com.newtonite.ops.common.PageResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Append-only history. {@link #record} must run inside the caller's transaction
 * (MANDATORY), so an event can never be written without the change it describes, or vice versa.
 */
@Service
public class ActivityService {

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    public ActivityService(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long itemId, Long actorId, String type, Map<String, Object> data) {
        jdbc.update("""
                INSERT INTO activity_events (item_id, actor_id, type, data)
                VALUES (:itemId, :actorId, :type, CAST(:data AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("itemId", itemId)
                .addValue("actorId", actorId)
                .addValue("type", type)
                .addValue("data", json.write(data)));
    }

    /** Newest first. Caller must have checked the user may see the item. */
    public PageResponse<ActivityEntry> list(long itemId, String cursorValue, int limit) {
        Cursor cursor = Cursor.decode(cursorValue);
        MapSqlParameterSource params = new MapSqlParameterSource("itemId", itemId).addValue("limit", limit + 1);
        String cursorClause = "";
        if (cursor != null) {
            cursorClause = "AND (e.created_at, e.id) < (:cAt, :cId)";
            params.addValue("cAt", cursor.atUtc()).addValue("cId", cursor.id());
        }
        List<ActivityEntry> rows = jdbc.query("""
                SELECT e.id, e.type, e.data::text AS data, e.created_at, e.actor_id, u.display_name AS actor_name
                FROM activity_events e LEFT JOIN users u ON u.id = e.actor_id
                WHERE e.item_id = :itemId %s
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT :limit
                """.formatted(cursorClause), params, (rs, i) -> new ActivityEntry(
                rs.getLong("id"), rs.getString("type"), json.read(rs.getString("data")),
                rs.getObject("actor_id", Long.class), rs.getString("actor_name"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            ActivityEntry last = rows.get(limit - 1);
            next = Cursor.encode(last.createdAt(), last.id());
        }
        return new PageResponse<>(rows, next);
    }

    public record ActivityEntry(long id, String type, JsonNode data, Long actorId, String actorName,
                                Instant createdAt) {
    }
}
