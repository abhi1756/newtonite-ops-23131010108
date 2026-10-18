package com.newtonite.ops.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.identity.MembershipRepository;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.outbox.JobHandler;
import com.newtonite.ops.outbox.OutboxService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Turns outbox jobs into in-app notifications.
 *
 * <p>Idempotent: before inserting, the handler checks whether this job already notified this user.
 * The check is race-free because the worker holds a row lock on the job while the handler runs, so the
 * same job is never handled twice at the same time. The unique constraint on
 * {@code (source_job_id, user_id)} remains as a last line of defence.
 */
@Configuration
public class NotificationHandlers {

    private final NotificationRepository notifications;
    private final MembershipRepository memberships;

    public NotificationHandlers(NotificationRepository notifications, MembershipRepository memberships) {
        this.notifications = notifications;
        this.memberships = memberships;
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
            List<Long> leads = memberships.findUserIdsWithRole(p.get("teamId").asLong(), TeamRole.LEAD,
                    p.get("actorId").asLong());
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
        if (!notifications.existsBySourceJobIdAndUserId(jobId, userId)) {
            notifications.save(new Notification(userId, itemId, message, jobId));
        }
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
