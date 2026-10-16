package com.newtonite.ops.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.outbox.JobHandler;
import com.newtonite.ops.outbox.OutboxService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

/**
 * Turns outbox jobs into in-app notifications. Each insert uses
 * {@code ON CONFLICT (source_job_id, user_id) DO NOTHING}, so running the same job twice
 * cannot notify anyone twice.
 */
@Configuration
public class NotificationHandlers {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationHandlers(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Bean
    JobHandler notifyAssignee() {
        return handler(OutboxService.NOTIFY_ASSIGNEE, (jobId, p) -> notify(jobId, p.get("userId").asLong(),
                p.get("itemId").asLong(), p.get("actorName").asText() + " assigned you: " + p.get("title").asText()));
    }

    @Bean
    JobHandler notifyApprovers() {
        return handler(OutboxService.NOTIFY_APPROVERS, (jobId, p) -> {
            // Resolved when the job runs, so Leads added in the meantime are included.
            List<Long> leads = jdbc.queryForList("""
                    SELECT user_id FROM memberships WHERE team_id = :teamId AND role = 'LEAD' AND user_id <> :actorId
                    """, new MapSqlParameterSource("teamId", p.get("teamId").asLong())
                    .addValue("actorId", p.get("actorId").asLong()), Long.class);
            String message = p.get("actorName").asText() + " requests your approval: " + p.get("title").asText();
            leads.forEach(lead -> notify(jobId, lead, p.get("itemId").asLong(), message));
        });
    }

    @Bean
    JobHandler notifyDecision() {
        return handler(OutboxService.NOTIFY_DECISION, (jobId, p) -> {
            String verb = "APPROVED".equals(p.get("decision").asText()) ? "approved" : "rejected";
            String note = p.hasNonNull("note") ? " (" + p.get("note").asText() + ")" : "";
            notify(jobId, p.get("userId").asLong(), p.get("itemId").asLong(),
                    p.get("actorName").asText() + " " + verb + ": " + p.get("title").asText() + note);
        });
    }

    private void notify(long jobId, long userId, long itemId, String message) {
        jdbc.update("""
                INSERT INTO notifications (user_id, item_id, message, source_job_id)
                VALUES (:userId, :itemId, left(:message, 500), :jobId)
                ON CONFLICT (source_job_id, user_id) DO NOTHING
                """, new MapSqlParameterSource("userId", userId).addValue("itemId", itemId)
                .addValue("message", message).addValue("jobId", jobId));
    }

    private static JobHandler handler(String type, Body body) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle(long jobId, JsonNode payload) {
                body.handle(jobId, payload);
            }
        };
    }

    @FunctionalInterface
    private interface Body {
        void handle(long jobId, JsonNode payload);
    }
}
