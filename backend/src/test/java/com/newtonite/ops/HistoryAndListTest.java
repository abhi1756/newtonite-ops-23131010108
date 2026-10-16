package com.newtonite.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** History is complete and consistent with the item; lists are filtered and paginated by the server. */
class HistoryAndListTest extends IntegrationTest {

    @Test
    void everyImportantChangeAppearsInTheHistoryNewestFirst() {
        long team = newTeam();
        TestUser member = member(team, TeamRole.MEMBER);
        long id = createItem(member, team, "INCIDENT").get("id").asLong();
        long v = post(member, "/api/items/" + id + "/claim", null).body().get("version").asLong();
        v = patch(member, "/api/items/" + id, Map.of("version", v, "priority", "URGENT")).body().get("version").asLong();
        post(member, "/api/items/" + id + "/transition", Map.of("to", "IN_PROGRESS", "version", v));
        post(member, "/api/items/" + id + "/comments", Map.of("body", "Rolled back the deploy"));

        JsonNode events = get(member, "/api/items/" + id + "/activity").body().get("items");

        List<String> types = new ArrayList<>();
        events.forEach(e -> types.add(e.get("type").asText()));
        assertThat(types).containsExactly("COMMENTED", "STATUS_CHANGED", "PRIORITY_CHANGED", "ASSIGNED", "CREATED");
        assertThat(events.get(2).get("data").get("from").asText()).isEqualTo("HIGH");
        assertThat(events.get(2).get("data").get("to").asText()).isEqualTo("URGENT");
        assertThat(events.get(2).get("actorName").asText()).isEqualTo(member.name());
    }

    @Test
    void aRejectedChangeLeavesNoTraceInHistoryOrOutbox() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser member = member(team, TeamRole.MEMBER);
        long id = createItem(lead, team, "INCIDENT").get("id").asLong();

        Response r = post(lead, "/api/items/" + id + "/assign", Map.of("assigneeId", member.id(), "version", 42));

        assertThat(r.status()).isEqualTo(409);
        assertThat(countEvents(id, "ASSIGNED")).isZero();
        assertThat(countJobs("NOTIFY_ASSIGNEE", id)).isZero();
    }

    @Test
    void cursorPaginationVisitsEveryItemOnceEvenWhileNewItemsArrive() {
        long team = newTeam();
        TestUser member = member(team, TeamRole.MEMBER);
        Set<Long> created = new HashSet<>();
        for (int i = 0; i < 23; i++) {
            created.add(createItem(member, team, "TASK").get("id").asLong());
        }

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/api/items?limit=10&teamId=" + team + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = get(member, path).body();
            page.get("items").forEach(i -> seen.add(i.get("id").asLong()));
            cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
            if (pages++ == 0) {
                createItem(member, team, "TASK");   // arrives after page 1 was read: newer, so not in later pages
            }
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(23).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(created);
    }

    @Test
    void searchMatchesTextAndIdsAndFiltersCombine() {
        long team = newTeam();
        TestUser member = member(team, TeamRole.MEMBER);
        long target = createItem(member, team, "PAYMENT", "Settlement mismatch for merchant Wombat").get("id").asLong();
        createItem(member, team, "TASK", "Unrelated housekeeping");

        assertThat(ids(get(member, "/api/items?q=wombat"))).containsExactly(target);
        assertThat(ids(get(member, "/api/items?q=%23" + target))).containsExactly(target);
        assertThat(ids(get(member, "/api/items?teamId=" + team + "&type=PAYMENT,COMPLIANCE"))).containsExactly(target);
        assertThat(ids(get(member, "/api/items?q=wombat&type=TASK"))).isEmpty();
    }

    @Test
    void dashboardViewsAndCountsReflectTheCaller() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser member = member(team, TeamRole.MEMBER);
        long mine = createItem(member, team, "TASK").get("id").asLong();
        long unassigned = createItem(member, team, "INCIDENT").get("id").asLong();   // HIGH priority
        post(member, "/api/items/" + mine + "/claim", null);

        assertThat(ids(get(member, "/api/items?view=mine"))).containsExactly(mine);
        assertThat(ids(get(member, "/api/items?view=unassigned_urgent&teamId=" + team))).containsExactly(unassigned);
        assertThat(get(member, "/api/items/summary").body().get("mine").asLong()).isEqualTo(1);
        assertThat(get(lead, "/api/items/summary").body().get("mine").asLong()).isZero();
        assertThat(get(member, "/api/items?view=bogus").status()).isEqualTo(400);
    }

    private static List<Long> ids(Response r) {
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        r.body().get("items").forEach(i -> ids.add(i.get("id").asLong()));
        return ids;
    }
}
