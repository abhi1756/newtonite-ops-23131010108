package com.newtonite.ops.auth;

import com.newtonite.ops.identity.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** Issues short-lived HS256 tokens. The token only identifies the user; roles are read from the DB per request. */
@Service
public class JwtService {

    static final String ISSUER = "newtonite-ops";

    private final JwtEncoder encoder;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, @Value("${app.jwt.ttl}") Duration ttl) {
        this.encoder = encoder;
        this.ttl = ttl;
    }

    public IssuedToken issue(UserAccount user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(Long.toString(user.id()))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", user.email())
                .claim("name", user.displayName())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
