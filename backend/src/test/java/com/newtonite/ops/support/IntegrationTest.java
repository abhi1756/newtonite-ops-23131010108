package com.newtonite.ops.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newtonite.ops.auth.JwtService;
import com.newtonite.ops.activity.ActivityEventRepository;
import com.newtonite.ops.identity.Membership;
import com.newtonite.ops.identity.MembershipRepository;
import com.newtonite.ops.identity.Team;
import com.newtonite.ops.identity.TeamRepository;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.identity.User;
import com.newtonite.ops.identity.UserRepository;
import com.newtonite.ops.item.WorkItem;
import com.newtonite.ops.item.WorkItemRepository;
import com.newtonite.ops.outbox.OutboxJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for API-level tests: a real server on a random port, a real PostgreSQL, and real HTTP.
 * Every test creates its own teams and users with unique names, so tests never depend on each other
 * and the schema never needs to be reset.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.locations=classpath:db/migration",   // schema only, no demo data
        "app.outbox.enabled=false"                           // tests drive the worker explicitly
})
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final String PASSWORD = "correct-horse-battery";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());

    @LocalServerPort
    protected int port;
    @Autowired
    protected ObjectMapper mapper;
    @Autowired
    protected WorkItemRepository workItems;
    @Autowired
    protected OutboxJobRepository outboxJobs;
    @Autowired
    private UserRepository users;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private ActivityEventRepository activityEvents;
    @Autowired
    private JwtService jwtService;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    // ------------------------------------------------------------------ fixtures

    public record TestUser(long id, String email, String name, String token) {
    }

    protected long newTeam() {
        return teams.save(new Team("team-" + SEQUENCE.incrementAndGet())).getId();
    }

    protected TestUser newUser(String label, boolean admin) {
        long n = SEQUENCE.incrementAndGet();
        String email = label + "-" + n + "@test.local";
        String name = label + " " + n;
        User user = users.save(new User(email, name, PASSWORD_HASH, admin));
        String token = jwtService.issue(user.toAccount()).token();
        return new TestUser(user.getId(), email, name, token);
    }

    protected TestUser member(long teamId, TeamRole role) {
        TestUser user = newUser(role.name().toLowerCase(), false);
        addMembership(user, teamId, role);
        return user;
    }

    protected void addMembership(TestUser user, long teamId, TeamRole role) {
        memberships.save(new Membership(user.id(), teamId, role));
    }

    /** Creates an item through the API and returns its JSON. */
    protected JsonNode createItem(TestUser user, long teamId, String type, String title) {
        Response r = post(user, "/api/items", Map.of("teamId", teamId, "title", title, "type", type,
                "priority", "HIGH", "description", "created by a test"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.body();
    }

    protected JsonNode createItem(TestUser user, long teamId, String type) {
        return createItem(user, teamId, type, "Item " + SEQUENCE.incrementAndGet());
    }

    /** The item as currently stored (a fresh read, outside any transaction). */
    protected WorkItem item(long id) {
        return workItems.findById(id).orElseThrow();
    }

    protected int countEvents(long itemId, String type) {
        return (int) activityEvents.countByItemIdAndType(itemId, type);
    }

    protected int countJobs(String type, long itemId) {
        return (int) outboxJobs.findByType(type).stream()
                .filter(job -> ((Number) job.getPayload().get("itemId")).longValue() == itemId)
                .count();
    }

    // ------------------------------------------------------------------ HTTP

    public record Response(int status, JsonNode body, HttpHeaders headers) {
        public String code() {
            return body == null ? null : body.path("code").asText(null);
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    protected Response get(TestUser user, String path) {
        return send("GET", user, path, null);
    }

    protected Response post(TestUser user, String path, Object body, String... headers) {
        return send("POST", user, path, body, headers);
    }

    protected Response patch(TestUser user, String path, Object body, String... headers) {
        return send("PATCH", user, path, body, headers);
    }

    /** {@code headers} are name/value pairs. {@code user} may be null for anonymous calls. */
    protected Response send(String method, TestUser user, String path, Object body, String... headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (user != null) {
                builder.header("Authorization", "Bearer " + user.token());
            }
            for (int i = 0; i < headers.length; i += 2) {
                builder.header(headers[i], headers[i + 1]);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = response.body() == null || response.body().isBlank() ? null : mapper.readTree(response.body());
            return new Response(response.statusCode(), json, response.headers());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ concurrency

    /** Runs all tasks at the same instant (released together by a latch) and returns their results in order. */
    protected <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
