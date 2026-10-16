package com.newtonite.ops.authz;

import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.identity.CurrentUser;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.item.WorkItem;
import org.springframework.stereotype.Service;

/**
 * The single place that decides what a user may do with a team's data.
 * Every controller/service path goes through here; the UI only mirrors these answers.
 *
 * <ul>
 *   <li>Not in the item's team: 404, so the item's existence is not revealed.</li>
 *   <li>In the team but the role is too low: 403.</li>
 *   <li>Global admins act as a Lead of every team.</li>
 * </ul>
 */
@Service
public class AuthorizationService {

    /** Effective role of the user in a team, or null if the user cannot see the team at all. */
    public TeamRole roleIn(CurrentUser user, long teamId) {
        if (user.admin()) {
            return TeamRole.LEAD;
        }
        return user.roles().get(teamId);
    }

    public TeamRole requireVisible(CurrentUser user, long teamId, String what) {
        TeamRole role = roleIn(user, teamId);
        if (role == null) {
            throw ApiException.notFound(what);
        }
        return role;
    }

    public TeamRole requireRole(CurrentUser user, long teamId, TeamRole minimum, String what, String action) {
        TeamRole role = requireVisible(user, teamId, what);
        if (!role.atLeast(minimum)) {
            throw ApiException.forbidden("A " + role + " of this team cannot " + action);
        }
        return role;
    }

    /** Assignee, the creator while nobody owns it yet, or a Lead. */
    public boolean canEdit(CurrentUser user, WorkItem item, TeamRole role) {
        if (role == null || !role.atLeast(TeamRole.MEMBER)) {
            return false;
        }
        if (role == TeamRole.LEAD) {
            return true;
        }
        Long assignee = item.getAssigneeId();
        return (assignee != null && assignee == user.id())
                || (assignee == null && item.getCreatedBy() == user.id());
    }

    public void requireCanEdit(CurrentUser user, WorkItem item, TeamRole role) {
        if (!canEdit(user, item, role)) {
            throw ApiException.forbidden("Only the assignee, the creator of an unassigned item, or a Lead can edit this item");
        }
    }
}
