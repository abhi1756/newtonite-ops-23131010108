package com.newtonite.ops.item;

import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.identity.TeamRole;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.newtonite.ops.item.Status.AWAITING_APPROVAL;
import static com.newtonite.ops.item.Status.CLOSED;
import static com.newtonite.ops.item.Status.IN_PROGRESS;
import static com.newtonite.ops.item.Status.OPEN;
import static com.newtonite.ops.item.Status.RESOLVED;

/**
 * The workflow as data: the only status changes the server accepts, and who may perform them.
 * Adding a status means adding an enum constant, a migration for the CHECK constraint, and rows here.
 */
@Component
public class WorkflowPolicy {

    public enum Actor { ASSIGNEE_OR_LEAD, LEAD }

    public record Rule(Status from, Status to, String action, Actor actor) {
    }

    static final List<Rule> RULES = List.of(
            new Rule(OPEN, IN_PROGRESS, "start", Actor.ASSIGNEE_OR_LEAD),
            new Rule(IN_PROGRESS, AWAITING_APPROVAL, "submit for approval", Actor.ASSIGNEE_OR_LEAD),
            new Rule(IN_PROGRESS, RESOLVED, "resolve", Actor.ASSIGNEE_OR_LEAD),
            new Rule(AWAITING_APPROVAL, RESOLVED, "approve", Actor.LEAD),
            new Rule(AWAITING_APPROVAL, IN_PROGRESS, "reject", Actor.LEAD),
            new Rule(RESOLVED, IN_PROGRESS, "reopen", Actor.ASSIGNEE_OR_LEAD),
            new Rule(RESOLVED, CLOSED, "close", Actor.LEAD));

    public Optional<Rule> find(Status from, Status to) {
        return RULES.stream().filter(r -> r.from() == from && r.to() == to).findFirst();
    }

    /**
     * Validates a transition. Workflow violations are 422 (the request can never succeed in this state);
     * a valid move by the wrong person is 403.
     */
    public Rule check(WorkItem item, Status to, long actorId, TeamRole role, String note) {
        Rule rule = find(item.getStatus(), to).orElseThrow(() -> ApiException.unprocessable("INVALID_TRANSITION",
                "Cannot move an item from " + item.getStatus() + " to " + to));
        String problem = structuralProblem(item, rule, note);
        if (problem != null) {
            throw ApiException.unprocessable("INVALID_TRANSITION", problem);
        }
        if (!actorAllowed(rule, item, actorId, role)) {
            throw ApiException.forbidden(rule.actor() == Actor.LEAD
                    ? "Only a Lead of this team can " + rule.action() + " this item"
                    : "Only the assignee or a Lead can " + rule.action() + " this item");
        }
        return rule;
    }

    /** Transitions the given user could perform right now (used by the UI to show buttons). */
    public List<Rule> available(WorkItem item, long actorId, TeamRole role) {
        if (role == null || !role.atLeast(TeamRole.MEMBER)) {
            return List.of();
        }
        return RULES.stream()
                .filter(r -> r.from() == item.getStatus())
                .filter(r -> structuralProblem(item, r, "n/a") == null)
                .filter(r -> actorAllowed(r, item, actorId, role))
                .toList();
    }

    private String structuralProblem(WorkItem item, Rule rule, String note) {
        boolean needsApproval = item.getType().requiresApproval();
        if (rule.to() == AWAITING_APPROVAL && !needsApproval) {
            return item.getType() + " items do not need approval; resolve them directly";
        }
        if (rule.from() == IN_PROGRESS && rule.to() == RESOLVED && needsApproval) {
            return item.getType() + " items must be approved by a Lead before they are resolved";
        }
        if (rule.from() == OPEN && rule.to() == IN_PROGRESS && item.getAssigneeId() == null) {
            return "Assign the item before starting work on it";
        }
        if (rule.from() == AWAITING_APPROVAL && rule.to() == IN_PROGRESS && (note == null || note.isBlank())) {
            return "A note explaining the rejection is required";
        }
        return null;
    }

    private boolean actorAllowed(Rule rule, WorkItem item, long actorId, TeamRole role) {
        if (role == TeamRole.LEAD) {
            return true;
        }
        if (rule.actor() == Actor.LEAD || role != TeamRole.MEMBER) {
            return false;
        }
        return item.getAssigneeId() != null && item.getAssigneeId() == actorId;
    }
}
