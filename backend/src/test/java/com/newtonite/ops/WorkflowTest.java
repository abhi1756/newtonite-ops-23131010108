package com.newtonite.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The workflow is enforced by the server, including who may approve. */
class WorkflowTest extends IntegrationTest {

    private Response transition(TestUser user, long id, String to, String note) {
        long version = item(id).getVersion();
        Map<String, Object> body = new HashMap<>(Map.of("to", to, "version", version));
        if (note != null) {
            body.put("note", note);
        }
        return post(user, "/api/items/" + id + "/transition", body);
    }

    private String status(long id) {
        return item(id).getStatus().name();
    }

    @Test
    void invalidJumpIsRejected() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        long id = createItem(lead, team, "TASK").get("id").asLong();

        Response r = transition(lead, id, "CLOSED", null);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("INVALID_TRANSITION");
        assertThat(status(id)).isEqualTo("OPEN");
        assertThat(countEvents(id, "STATUS_CHANGED")).isZero();
    }

    @Test
    void workCannotStartWithoutAnOwner() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        long id = createItem(lead, team, "TASK").get("id").asLong();

        Response r = transition(lead, id, "IN_PROGRESS", null);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body().get("detail").asText()).contains("Assign");
    }

    @Test
    void paymentMustBeApprovedByALeadOfItsTeam() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser member = member(team, TeamRole.MEMBER);
        TestUser leadElsewhere = member(newTeam(), TeamRole.LEAD);
        addMembership(leadElsewhere, team, TeamRole.MEMBER);   // a Lead, but only a Member in THIS team
        long id = createItem(member, team, "PAYMENT").get("id").asLong();
        assertThat(post(member, "/api/items/" + id + "/claim", null).status()).isEqualTo(200);
        assertThat(transition(member, id, "IN_PROGRESS", null).status()).isEqualTo(200);

        Response skipApproval = transition(member, id, "RESOLVED", null);
        assertThat(skipApproval.status()).isEqualTo(422);

        assertThat(transition(member, id, "AWAITING_APPROVAL", null).status()).isEqualTo(200);
        assertThat(countJobs("NOTIFY_APPROVERS", id)).isEqualTo(1);

        assertThat(transition(member, id, "RESOLVED", null).status()).isEqualTo(403);
        assertThat(transition(leadElsewhere, id, "RESOLVED", null).status()).isEqualTo(403);
        assertThat(transition(lead, id, "IN_PROGRESS", null).status()).as("rejection needs a note").isEqualTo(422);

        assertThat(transition(lead, id, "IN_PROGRESS", "Wrong merchant account").status()).isEqualTo(200);
        assertThat(transition(member, id, "AWAITING_APPROVAL", null).status()).isEqualTo(200);
        Response approved = transition(lead, id, "RESOLVED", null);

        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("status").asText()).isEqualTo("RESOLVED");
        assertThat(countJobs("NOTIFY_DECISION", id)).isEqualTo(2);   // one rejection, one approval
    }

    @Test
    void nonApprovalTypesResolveDirectly() {
        long team = newTeam();
        TestUser member = member(team, TeamRole.MEMBER);
        long id = createItem(member, team, "INCIDENT").get("id").asLong();
        post(member, "/api/items/" + id + "/claim", null);
        transition(member, id, "IN_PROGRESS", null);

        assertThat(transition(member, id, "AWAITING_APPROVAL", null).status()).isEqualTo(422);
        assertThat(transition(member, id, "RESOLVED", null).status()).isEqualTo(200);
        assertThat(transition(member, id, "CLOSED", null).status()).as("only a Lead closes").isEqualTo(403);
    }

    @Test
    void onlyTheAssigneeOrALeadMovesAnItem() {
        long team = newTeam();
        TestUser owner = member(team, TeamRole.MEMBER);
        TestUser colleague = member(team, TeamRole.MEMBER);
        long id = createItem(owner, team, "TASK").get("id").asLong();
        post(owner, "/api/items/" + id + "/claim", null);

        assertThat(transition(colleague, id, "IN_PROGRESS", null).status()).isEqualTo(403);
        assertThat(transition(owner, id, "IN_PROGRESS", null).status()).isEqualTo(200);
    }

    @Test
    void detailAdvertisesExactlyTheTransitionsTheServerWouldAccept() {
        long team = newTeam();
        TestUser lead = member(team, TeamRole.LEAD);
        TestUser member = member(team, TeamRole.MEMBER);
        long id = createItem(member, team, "COMPLIANCE").get("id").asLong();
        post(member, "/api/items/" + id + "/claim", null);
        transition(member, id, "IN_PROGRESS", null);
        transition(member, id, "AWAITING_APPROVAL", null);

        assertThat(actions(get(lead, "/api/items/" + id).body())).containsExactlyInAnyOrder("approve", "reject");
        assertThat(actions(get(member, "/api/items/" + id).body())).isEmpty();
    }

    private static List<String> actions(JsonNode detail) {
        List<String> actions = new ArrayList<>();
        detail.get("permissions").get("transitions").forEach(t -> actions.add(t.get("action").asText()));
        return actions;
    }
}
