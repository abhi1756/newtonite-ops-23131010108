package com.newtonite.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.outbox.OutboxWorker;
import com.newtonite.ops.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reliable asynchronous work: jobs are delivered once even if processed twice, failures are retried
 * and eventually parked, and parallel workers never take the same job.
 * The scheduler is disabled in tests; the worker is driven explicitly.
 */
class OutboxWorkerTest extends IntegrationTest {

    @Autowired
    private OutboxWorker worker;

    private void drain() {
        while (worker.processNext()) {
            // keep going until no job is due
        }
    }

    private long insertJob(String type, String payloadJson, int maxAttempts) {
        return jdbc.queryForObject("""
                INSERT INTO outbox_jobs (type, payload, max_attempts) VALUES (?, CAST(? AS jsonb), ?) RETURNING id
                """, Long.class, type, payloadJson, maxAttempts);
    }

    private String jobStatus(long jobId) {
        return jdbc.queryForObject("SELECT status FROM outbox_jobs WHERE id = ?", String.class, jobId);
    }

    private int notificationsFor(TestUser user) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class, user.id());
    }

    @Test
    void assignmentNotifiesTheAssigneeThroughTheWorkerAndTheApi() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser member = member(team, TeamRole.MEMBER);
        JsonNode item = createItem(lead, team, "INCIDENT", "Queue backlog growing");
        post(lead, "/api/items/" + item.get("id").asLong() + "/assign",
                Map.of("assigneeId", member.id(), "version", item.get("version").asLong()));

        assertThat(notificationsFor(member)).as("nothing happens before the worker runs").isZero();
        drain();

        JsonNode page = get(member, "/api/notifications").body();
        assertThat(page.get("unreadCount").asLong()).isEqualTo(1);
        JsonNode n = page.get("items").get(0);
        assertThat(n.get("message").asText()).contains(lead.name()).contains("Queue backlog growing");

        assertThat(post(member, "/api/notifications/" + n.get("id").asLong() + "/read", null).status()).isEqualTo(200);
        assertThat(get(member, "/api/notifications").body().get("unreadCount").asLong()).isZero();
        assertThat(post(lead, "/api/notifications/" + n.get("id").asLong() + "/read", null).status())
                .as("cannot touch someone else's notification").isEqualTo(404);
    }

    @Test
    void processingTheSameJobTwiceNotifiesOnlyOnce() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser member = member(team, TeamRole.MEMBER);
        long itemId = createItem(lead, team, "TASK").get("id").asLong();
        long jobId = insertJob("NOTIFY_ASSIGNEE", """
                {"itemId": %d, "userId": %d, "title": "t", "actorName": "Lead"}""".formatted(itemId, member.id()), 5);

        drain();
        // Simulate redelivery, e.g. the worker crashed after the handler ran but before the job was marked DONE.
        jdbc.update("UPDATE outbox_jobs SET status = 'PENDING' WHERE id = ?", jobId);
        drain();

        assertThat(jobStatus(jobId)).isEqualTo("DONE");
        assertThat(notificationsFor(member)).isEqualTo(1);
    }

    @Test
    void failingJobIsRetriedWithBackoffThenParkedAsFailed() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        long itemId = createItem(lead, team, "TASK").get("id").asLong();
        // Recipient does not exist: the insert violates a foreign key every time.
        long jobId = insertJob("NOTIFY_ASSIGNEE", """
                {"itemId": %d, "userId": 999999999, "title": "t", "actorName": "Lead"}""".formatted(itemId), 3);

        drain();
        assertThat(jobStatus(jobId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_jobs WHERE id = ?", Integer.class, jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT run_after > now() FROM outbox_jobs WHERE id = ?", Boolean.class, jobId))
                .as("next attempt is delayed").isTrue();

        for (int attempt = 2; attempt <= 3; attempt++) {
            jdbc.update("UPDATE outbox_jobs SET run_after = now() WHERE id = ?", jobId);   // skip the wait
            drain();
        }

        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_jobs WHERE id = ?", Integer.class, jobId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_jobs WHERE id = ?", String.class, jobId)).isNotBlank();
        jdbc.update("UPDATE outbox_jobs SET run_after = now() WHERE id = ?", jobId);
        drain();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_jobs WHERE id = ?", Integer.class, jobId))
                .as("FAILED jobs are not picked up again").isEqualTo(3);
    }

    @Test
    void unknownJobTypesFailImmediatelyInsteadOfRetrying() {
        long jobId = insertJob("NO_SUCH_TYPE", "{}", 5);

        drain();

        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
    }

    @Test
    void parallelWorkersProcessEachJobExactlyOnce() throws Exception {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser member = member(team, TeamRole.MEMBER);
        long itemId = createItem(lead, team, "TASK").get("id").asLong();
        drain();
        List<Long> jobs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            jobs.add(insertJob("NOTIFY_ASSIGNEE", """
                    {"itemId": %d, "userId": %d, "title": "job %d", "actorName": "Lead"}""".formatted(itemId, member.id(), i), 5));
        }

        List<Callable<Boolean>> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            workers.add(() -> {
                drain();
                return true;
            });
        }
        runConcurrently(workers);

        for (long jobId : jobs) {
            assertThat(jobStatus(jobId)).isEqualTo("DONE");
            assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_jobs WHERE id = ?", Integer.class, jobId))
                    .as("job %d handled by exactly one worker", jobId).isEqualTo(1);
        }
        assertThat(notificationsFor(member)).isEqualTo(30);
    }
}
