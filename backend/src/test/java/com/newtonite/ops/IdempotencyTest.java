package com.newtonite.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** A user repeating a request (double click, retry after timeout) must not repeat its effect. */
class IdempotencyTest extends IntegrationTest {

    private static final String KEY = "Idempotency-Key";

    private Map<String, Object> newItem(long team, String title) {
        return Map.of("teamId", team, "title", title, "type", "TASK", "priority", "LOW");
    }

    private int itemsInTeam(long team) {
        return (int) workItems.countByTeamId(team);
    }

    @Test
    void repeatingACreateWithTheSameKeyCreatesOneItemAndReturnsTheSameResponse() {
        long team = newTeam();
        TestUser user = member(team, TeamRole.MEMBER);
        String key = UUID.randomUUID().toString();

        Response first = post(user, "/api/items", newItem(team, "Refund check"), KEY, key);
        Response retry = post(user, "/api/items", newItem(team, "Refund check"), KEY, key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(itemsInTeam(team)).isEqualTo(1);
    }

    @Test
    void simultaneousDuplicatesCreateOneItem() throws Exception {
        long team = newTeam();
        TestUser user = member(team, TeamRole.MEMBER);
        String key = UUID.randomUUID().toString();

        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> post(user, "/api/items", newItem(team, "Double click"), KEY, key));
        }
        List<Response> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.toString()).isEqualTo(201));
        assertThat(results.stream().map(r -> r.body().get("id").asLong()).distinct()).hasSize(1);
        assertThat(itemsInTeam(team)).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        long team = newTeam();
        TestUser user = member(team, TeamRole.MEMBER);
        String key = UUID.randomUUID().toString();

        post(user, "/api/items", newItem(team, "First"), KEY, key);
        Response reused = post(user, "/api/items", newItem(team, "Something else"), KEY, key);

        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(itemsInTeam(team)).isEqualTo(1);
    }

    @Test
    void aFailedRequestDoesNotConsumeItsKey() {
        long team = newTeam();
        TestUser user = member(team, TeamRole.LEAD);
        JsonNode item = createItem(user, team, "TASK");
        long id = item.get("id").asLong();
        String key = UUID.randomUUID().toString();

        Response failed = patch(user, "/api/items/" + id, Map.of("version", 999, "title", "x"), KEY, key);
        Response retried = patch(user, "/api/items/" + id,
                Map.of("version", item.get("version").asLong(), "title", "x"), KEY, key);

        assertThat(failed.status()).isEqualTo(409);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
    }

    @Test
    void keysAreScopedToTheUser() {
        long team = newTeam();
        TestUser alice = member(team, TeamRole.MEMBER);
        TestUser bob = member(team, TeamRole.MEMBER);
        String key = "same-key-" + UUID.randomUUID();

        post(alice, "/api/items", newItem(team, "Shared title"), KEY, key);
        post(bob, "/api/items", newItem(team, "Shared title"), KEY, key);

        assertThat(itemsInTeam(team)).isEqualTo(2);
    }
}
