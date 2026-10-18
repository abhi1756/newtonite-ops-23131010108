package com.newtonite.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.notification.NotificationRepository;
import com.newtonite.ops.outbox.OutboxJob;
import com.newtonite.ops.outbox.OutboxJob.JobStatus;
import com.newtonite.ops.outbox.OutboxWorker;
import com.newtonite.ops.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reliable asynchronous work: jobs are delivered once even if processed twice, failures are retried
 * and eventually parked, and parallel workers never take the same job.
 * The scheduler is disabled in tests; the worker is driven explicitly.
 */
class OutboxWorkerTest extends IntegrationTest {

    @Autowired
    private OutboxWorker worker;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private TransactionTemplate tx;

    private void drain() {
        while (worker.processNext()) {
            // keep going until no job is due
        }
    }

    private long insertJob(String type, Map<String, Object> payload, int maxAttempts) {
        return outboxJobs.save(new OutboxJob(type, payload, maxAttempts)).getId();
    }

    private OutboxJob job(long jobId) {
        return outboxJobs.findById(jobId).orElseThrow();
    }

    /** Makes a delayed job due now, instead of waiting for its backoff. */
    private void makeDue(long jobId) {
        OutboxJob job = job(jobId);
        job.setRunAfter(Instant.now().minusSeconds(1));
        outboxJobs.save(job);
    }

    private long notificationsFor(TestUser user) {
        return notifications.countByUserId(user.id());
    }

    private static Map<String, Object> notifyPayload(long itemId, long userId) {
        return Map.of("itemId", itemId, "userId", userId, "title", "t", "actorName", "Lead");
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
        long jobId = insertJob("NOTIFY_ASSIGNEE", notifyPayload(itemId, member.id()), 5);

        drain();
        // Simulate redelivery, e.g. the worker crashed after the handler ran but before the job was marked DONE.
        OutboxJob done = job(jobId);
        done.setStatus(JobStatus.PENDING);
        outboxJobs.save(done);
        drain();

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(notificationsFor(member)).isEqualTo(1);
    }

    @Test
    void failingJobIsRetriedWithBackoffThenParkedAsFailed() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        long itemId = createItem(lead, team, "TASK").get("id").asLong();
        // Recipient does not exist: the insert violates a foreign key every time.
        long jobId = insertJob("NOTIFY_ASSIGNEE", notifyPayload(itemId, 999_999_999L), 3);

        drain();
        OutboxJob afterFirst = job(jobId);
        assertThat(afterFirst.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getRunAfter()).as("next attempt is delayed").isAfter(Instant.now());

        for (int attempt = 2; attempt <= 3; attempt++) {
            makeDue(jobId);
            drain();
        }

        OutboxJob parked = job(jobId);
        assertThat(parked.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(parked.getAttempts()).isEqualTo(3);
        assertThat(parked.getLastError()).isNotBlank();
        makeDue(jobId);
        drain();
        assertThat(job(jobId).getAttempts()).as("FAILED jobs are not picked up again").isEqualTo(3);
    }

    @Test
    void unknownJobTypesFailImmediatelyInsteadOfRetrying() {
        long jobId = insertJob("NO_SUCH_TYPE", Map.of(), 5);

        drain();

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void aJobLockedByAnotherWorkerIsSkippedNotWaitedFor() throws Exception {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser member = member(team, TeamRole.MEMBER);
        long itemId = createItem(lead, team, "TASK").get("id").asLong();
        drain();
        long first = insertJob("NOTIFY_ASSIGNEE", notifyPayload(itemId, member.id()), 5);
        long second = insertJob("NOTIFY_ASSIGNEE", notifyPayload(itemId, member.id()), 5);

        // Another "worker" locks the first job and keeps its transaction open.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Long> holder = threads.submit(() -> tx.execute(status -> {
                List<OutboxJob> mine = outboxJobs.lockDue(JobStatus.PENDING, Instant.now(), PageRequest.of(0, 1));
                locked.countDown();
                awaitQuietly(release);
                return mine.get(0).getId();
            }));
            locked.await();

            // Without SKIP LOCKED this call would block until the other transaction ends (and time out here).
            Future<Boolean> processed = threads.submit(worker::processNext);
            assertThat(processed.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(job(second).getStatus()).isEqualTo(JobStatus.DONE);
            assertThat(job(first).getStatus()).isEqualTo(JobStatus.PENDING);

            release.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo(first);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        drain();
        assertThat(job(first).getStatus()).isEqualTo(JobStatus.DONE);
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
            jobs.add(insertJob("NOTIFY_ASSIGNEE", notifyPayload(itemId, member.id()), 5));
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
            OutboxJob job = job(jobId);
            assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
            assertThat(job.getAttempts()).as("job %d handled by exactly one worker", jobId).isEqualTo(1);
        }
        assertThat(notificationsFor(member)).isEqualTo(30);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
