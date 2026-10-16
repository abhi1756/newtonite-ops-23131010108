package com.newtonite.ops.identity;

import com.newtonite.ops.authz.AuthorizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class MeController {

    private final UserDirectory users;
    private final AuthorizationService authz;

    public MeController(UserDirectory users, AuthorizationService authz) {
        this.users = users;
        this.authz = authz;
    }

    @GetMapping("/me")
    public Me me(CurrentUser user) {
        return new Me(user.id(), user.email(), user.displayName(), user.admin(), users.teamsOf(user));
    }

    /** Members of a team, e.g. for the "assign to" picker. Only visible to people in that team. */
    @GetMapping("/teams/{teamId}/members")
    public List<UserDirectory.TeamMember> members(CurrentUser user, @PathVariable long teamId) {
        authz.requireVisible(user, teamId, "Team");
        return users.membersOf(teamId);
    }

    public record Me(long id, String email, String displayName, boolean admin,
                     List<UserDirectory.TeamMembership> teams) {
    }
}
