package com.newtonite.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Authorization is enforced per resource by the server, regardless of what the UI shows. */
class AuthorizationTest extends IntegrationTest {

    @Test
    void outsidersCannotSeeOrTouchAnotherTeamsItem() {
        long team = newTeam();
        TestUser member = member(team, TeamRole.MEMBER);
        TestUser outsider = member(newTeam(), TeamRole.LEAD);   // a Lead, but of a different team
        JsonNode item = createItem(member, team, "TASK");
        long id = item.get("id").asLong();
        long version = item.get("version").asLong();

        assertThat(get(outsider, "/api/items/" + id).status()).isEqualTo(404);
        assertThat(get(outsider, "/api/items/" + id + "/activity").status()).isEqualTo(404);
        assertThat(get(outsider, "/api/items/" + id + "/comments").status()).isEqualTo(404);
        assertThat(patch(outsider, "/api/items/" + id, Map.of("version", version, "title", "hijack")).status()).isEqualTo(404);
        assertThat(post(outsider, "/api/items/" + id + "/claim", null).status()).isEqualTo(404);
        assertThat(post(outsider, "/api/items/" + id + "/transition", Map.of("to", "IN_PROGRESS", "version", version)).status()).isEqualTo(404);
        assertThat(post(outsider, "/api/items/" + id + "/comments", Map.of("body", "hi")).status()).isEqualTo(404);
        assertThat(post(outsider, "/api/items", Map.of("teamId", team, "title", "x", "type", "TASK", "priority", "LOW")).status()).isEqualTo(404);
        assertThat(get(outsider, "/api/teams/" + team + "/members").status()).isEqualTo(404);
        // Same response as an id that does not exist at all.
        assertThat(get(outsider, "/api/items/" + id).body().get("code")).isEqualTo(get(outsider, "/api/items/999999999").body().get("code"));
    }

    @Test
    void listsOnlyEverContainTheCallersTeams() {
        long team = newTeam();
        TestUser member = member(team, TeamRole.MEMBER);
        TestUser outsider = member(newTeam(), TeamRole.MEMBER);
        createItem(member, team, "TASK", "Quokka reconciliation backlog");

        assertThat(get(member, "/api/items?q=quokka").body().get("items")).hasSize(1);
        assertThat(get(outsider, "/api/items?q=quokka").body().get("items")).isEmpty();
        assertThat(get(outsider, "/api/items?teamId=" + team).body().get("items")).isEmpty();
    }

    @Test
    void viewersCanReadButNotChangeAnything() {
        long team = newTeam();
        TestUser member = member(team, TeamRole.MEMBER);
        TestUser viewer = member(team, TeamRole.VIEWER);
        long id = createItem(member, team, "TASK").get("id").asLong();

        assertThat(get(viewer, "/api/items/" + id).status()).isEqualTo(200);
        assertThat(post(viewer, "/api/items", Map.of("teamId", team, "title", "x", "type", "TASK", "priority", "LOW")).status()).isEqualTo(403);
        assertThat(post(viewer, "/api/items/" + id + "/claim", null).status()).isEqualTo(403);
        assertThat(post(viewer, "/api/items/" + id + "/comments", Map.of("body", "hi")).status()).isEqualTo(403);
        assertThat(get(viewer, "/api/items/" + id).body().get("permissions").get("canClaim").asBoolean()).isFalse();
    }

    @Test
    void membersCannotEditOrReassignSomeoneElsesItem() {
        long team = newTeam();
        TestUser owner = member(team, TeamRole.MEMBER);
        TestUser colleague = member(team, TeamRole.MEMBER);
        JsonNode item = createItem(owner, team, "TASK");
        long id = item.get("id").asLong();
        long version = post(owner, "/api/items/" + id + "/claim", null).body().get("version").asLong();

        Response edit = patch(colleague, "/api/items/" + id, Map.of("version", version, "title", "mine now"));
        Response assign = post(colleague, "/api/items/" + id + "/assign", Map.of("assigneeId", colleague.id(), "version", version));

        assertThat(edit.status()).isEqualTo(403);
        assertThat(assign.status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM work_items WHERE id = ?", Long.class, id)).isEqualTo(owner.id());
    }

    @Test
    void leadsAssignOnlyToMembersOfTheTeamAndTheAssigneeIsNotified() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser member = member(team, TeamRole.MEMBER);
        TestUser viewer = member(team, TeamRole.VIEWER);
        TestUser stranger = member(newTeam(), TeamRole.MEMBER);
        JsonNode item = createItem(lead, team, "INCIDENT");
        long id = item.get("id").asLong();
        long version = item.get("version").asLong();

        assertThat(post(lead, "/api/items/" + id + "/assign", Map.of("assigneeId", viewer.id(), "version", version)).status()).isEqualTo(422);
        assertThat(post(lead, "/api/items/" + id + "/assign", Map.of("assigneeId", stranger.id(), "version", version)).status()).isEqualTo(422);
        Response ok = post(lead, "/api/items/" + id + "/assign", Map.of("assigneeId", member.id(), "version", version));

        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("assignee").get("id").asLong()).isEqualTo(member.id());
        assertThat(countJobs("NOTIFY_ASSIGNEE", id)).isEqualTo(1);
    }

    @Test
    void globalAdminsActAsLeadsEverywhere() {
        long team = newTeam();
        TestUser member = member(team, TeamRole.MEMBER);
        TestUser admin = newUser("admin", true);
        JsonNode item = createItem(member, team, "TASK");

        Response detail = get(admin, "/api/items/" + item.get("id").asLong());

        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("permissions").get("canAssign").asBoolean()).isTrue();
    }
}
