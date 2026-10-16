package com.newtonite.ops;

import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiTest extends IntegrationTest {

    @Test
    void loginReturnsTokenThatIdentifiesTheUserAndTheirTeams() {
        long team = newTeam();
        TestUser user = member(team, TeamRole.LEAD);

        Response login = post(null, "/api/auth/login", Map.of("email", user.email().toUpperCase(), "password", PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        TestUser withLoginToken = new TestUser(user.id(), user.email(), user.name(), login.body().get("token").asText());

        Response me = get(withLoginToken, "/api/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("id").asLong()).isEqualTo(user.id());
        assertThat(me.body().get("teams").get(0).get("id").asLong()).isEqualTo(team);
        assertThat(me.body().get("teams").get(0).get("role").asText()).isEqualTo("LEAD");
    }

    @Test
    void wrongPasswordAndUnknownEmailAreRejectedTheSameWay() {
        TestUser user = newUser("someone", false);

        Response wrongPassword = post(null, "/api/auth/login", Map.of("email", user.email(), "password", "nope"));
        Response unknownEmail = post(null, "/api/auth/login", Map.of("email", "nobody@test.local", "password", "nope"));

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(wrongPassword.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(unknownEmail.body().get("detail")).isEqualTo(wrongPassword.body().get("detail"));
    }

    @Test
    void apiRequiresAValidToken() {
        TestUser forged = new TestUser(1, "x@test.local", "x", "not-a-jwt");

        assertThat(get(null, "/api/items").status()).isEqualTo(401);
        assertThat(get(forged, "/api/items").status()).isEqualTo(401);
    }
}
