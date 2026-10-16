package com.newtonite.ops.auth;

import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.identity.UserAccount;
import com.newtonite.ops.identity.UserDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserDirectory users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    public AuthController(UserDirectory users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Optional<UserAccount> user = users.findByEmail(request.email());
        String hash = user.map(UserAccount::passwordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
        }
        JwtService.IssuedToken token = jwtService.issue(user.get());
        return new LoginResponse(token.token(), token.expiresAt());
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record LoginResponse(String token, Instant expiresAt) {
    }
}
