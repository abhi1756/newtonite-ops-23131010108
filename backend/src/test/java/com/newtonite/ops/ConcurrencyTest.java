package com.newtonite.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** Simultaneous actions on the same item: exactly one wins, the others are told why, nothing is lost. */
class ConcurrencyTest extends IntegrationTest {

    @Test
    void simultaneousClaimsHaveExactlyOneWinner() throws Exception {
        long team = newTeam();
        TestUser creator = member(team, TeamRole.MEMBER);
        long itemId = createItem(creator, team, "INCIDENT").get("id").asLong();

        List<TestUser> claimers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            claimers.add(member(team, TeamRole.MEMBER));
        }
        List<Callable<Response>> tasks = new ArrayList<>();
        for (TestUser u : claimers) {
            tasks.add(() -> post(u, "/api/items/" + itemId + "/claim", null));
        }

        List<Response> results = runConcurrently(tasks);

        List<Long> winners = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            Response r = results.get(i);
            if (r.status() == 200) {
                winners.add(claimers.get(i).id());
            } else {
                assertThat(r.status()).as(r.toString()).isEqualTo(409);
                assertThat(r.code()).isEqualTo("ALREADY_CLAIMED");
            }
        }
        assertThat(winners).hasSize(1);
        long assignee = item(itemId).getAssigneeId();
        assertThat(assignee).isEqualTo(winners.get(0));
        assertThat(countEvents(itemId, "ASSIGNED")).isEqualTo(1);
        assertThat(item(itemId).getVersion()).isEqualTo(1L);
    }

    @Test
    void reclaimingYourOwnItemIsAHarmlessNoOp() {
        long team = newTeam();
        TestUser user = member(team, TeamRole.MEMBER);
        long itemId = createItem(user, team, "TASK").get("id").asLong();

        Response first = post(user, "/api/items/" + itemId + "/claim", null);
        Response second = post(user, "/api/items/" + itemId + "/claim", null);

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("version").asLong()).isEqualTo(first.body().get("version").asLong());
        assertThat(countEvents(itemId, "ASSIGNED")).isEqualTo(1);
    }

    @Test
    void updateBasedOnAStaleVersionIsRejectedAndChangesNothing() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        JsonNode item = createItem(lead, team, "TASK", "Original title");
        long id = item.get("id").asLong();
        long v0 = item.get("version").asLong();

        Response first = patch(lead, "/api/items/" + id, Map.of("version", v0, "title", "First edit"));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("version").asLong()).isEqualTo(v0 + 1);

        // A second user still looking at version v0 tries to save.
        Response stale = patch(lead, "/api/items/" + id, Map.of("version", v0, "title", "Overwrites first edit"));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("STALE_VERSION");
        assertThat(stale.body().get("current").get("title").asText()).isEqualTo("First edit");
        assertThat(stale.body().get("current").get("version").asLong()).isEqualTo(v0 + 1);
        assertThat(item(id).getTitle()).isEqualTo("First edit");
        assertThat(countEvents(id, "UPDATED")).isEqualTo(1);
    }

    @Test
    void simultaneousEditsOfTheSameVersionLetExactlyOneThrough() throws Exception {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        JsonNode item = createItem(lead, team, "TASK");
        long id = item.get("id").asLong();
        long version = item.get("version").asLong();

        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String title = "Concurrent title " + i;
            tasks.add(() -> patch(lead, "/api/items/" + id, Map.of("version", version, "title", title)));
        }
        List<Response> results = runConcurrently(tasks);

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 200)
                .allSatisfy(r -> assertThat(r.code()).as(r.toString()).isEqualTo("STALE_VERSION"));
        assertThat(item(id).getVersion()).isEqualTo(version + 1);
        assertThat(countEvents(id, "UPDATED")).isEqualTo(1);
    }

    @Test
    void commentsDoNotMakeOtherPeoplesEditsStale() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser colleague = member(team, TeamRole.MEMBER);
        JsonNode item = createItem(lead, team, "TASK");
        long id = item.get("id").asLong();

        assertThat(post(colleague, "/api/items/" + id + "/comments", Map.of("body", "Looking at logs")).status()).isEqualTo(201);
        Response edit = patch(lead, "/api/items/" + id, Map.of("version", item.get("version").asLong(), "priority", "URGENT"));

        assertThat(edit.status()).as(edit.toString()).isEqualTo(200);
    }
}
