package com.stockflow.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import com.stockflow.users.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}
    public record UserView(UUID id, String email, String role, boolean active, Instant createdAt, Instant updatedAt) {
        static UserView of(UserAccount u) { return new UserView(u.getId(), u.getEmail(), u.getRole(), u.isActive(), u.getCreatedAt(), u.getUpdatedAt()); }
    }
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserView user) {}
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final LoginLimiter limiter;
    private final String issuer, audience, dummyHash;

    public AuthController(UserRepository users, PasswordEncoder passwords, JwtEncoder encoder, LoginLimiter limiter,
            @Value("${app.jwt.issuer}") String issuer, @Value("${app.jwt.audience}") String audience) {
        this.users = users; this.passwords = passwords; this.encoder = encoder; this.limiter = limiter;
        this.issuer = issuer; this.audience = audience;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servlet,
            jakarta.servlet.http.HttpServletResponse response) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (!limiter.allow(servlet.getRemoteAddr(), email)) {
            response.setHeader("Retry-After", "60");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var user = users.findByEmail(email).orElse(null);
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null || !user.isActive()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(java.util.List.of(audience))
                .subject(user.getId().toString()).issuedAt(now).expiresAt(now.plusSeconds(900)).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", 900, UserView.of(user));
    }

    @GetMapping("/me")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public UserView me(@AuthenticationPrincipal Jwt jwt) {
        return users.findById(UUID.fromString(jwt.getSubject())).filter(UserAccount::isActive)
                .map(UserView::of).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
