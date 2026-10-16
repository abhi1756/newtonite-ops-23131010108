package com.newtonite.ops.notification;

import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.identity.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** The caller's own notifications. Users can only ever see and mark their own. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public NotificationPage list(CurrentUser user, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", user.id()).addValue("limit", limit);
        List<NotificationEntry> items = jdbc.query("""
                SELECT id, item_id, message, read_at IS NOT NULL AS read, created_at
                FROM notifications WHERE user_id = :userId
                ORDER BY created_at DESC, id DESC LIMIT :limit
                """, params, (rs, i) -> new NotificationEntry(rs.getLong("id"), rs.getObject("item_id", Long.class),
                rs.getString("message"), rs.getBoolean("read"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
        Long unread = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = :userId AND read_at IS NULL", params, Long.class);
        return new NotificationPage(items, unread == null ? 0 : unread);
    }

    @PostMapping("/{id}/read")
    public void markRead(CurrentUser user, @PathVariable long id) {
        int updated = jdbc.update("""
                UPDATE notifications SET read_at = coalesce(read_at, now()) WHERE id = :id AND user_id = :userId
                """, new MapSqlParameterSource("id", id).addValue("userId", user.id()));
        if (updated == 0) {
            throw ApiException.notFound("Notification " + id);
        }
    }

    @PostMapping("/read-all")
    public void markAllRead(CurrentUser user) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = :userId AND read_at IS NULL",
                new MapSqlParameterSource("userId", user.id()));
    }

    public record NotificationEntry(long id, Long itemId, String message, boolean read, Instant createdAt) {
    }

    public record NotificationPage(List<NotificationEntry> items, long unreadCount) {
    }
}
