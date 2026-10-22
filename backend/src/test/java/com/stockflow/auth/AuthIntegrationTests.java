package com.stockflow.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import com.stockflow.users.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import com.stockflow.common.error.RequestIdFilter;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.jwt.secret=integration-test-only-key-not-for-production-123456")
class AuthIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    UserAccount user;
    String email;
    final String password = "test-only-password-123";

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(RequestIdFilter.class), context.getBean(FilterChainProxy.class)).build();
        email = "test-" + UUID.randomUUID() + "@example.test";
        user = users.saveAndFlush(new UserAccount(email, passwords.encode(password), "STAFF"));
    }
    @AfterEach void cleanup() { users.deleteById(user.getId()); }

    String token(String issuer, String audience, Instant expiration, JwtEncoder signer) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(user.getId().toString()).issuedAt(Instant.now().minusSeconds(600))
                .expiresAt(expiration).build();
        return signer.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    String validToken() { return token("stockflow", "stockflow-web", Instant.now().plusSeconds(300), encoder); }
    String body(String value) { return "{\"email\":\"" + email + "\",\"password\":\"" + value + "\"}"; }

    @Test void loginAndProfile() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body(password)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresIn").value(900)).andExpect(jsonPath("$.user.passwordHash").doesNotExist());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }
    @Test void invalidAndInactiveCredentials() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body("wrong-password")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        jdbc.update("UPDATE app_users SET active=false WHERE id=?", user.getId());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body(password)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isUnauthorized());
    }
    @Test void anonymousAndStaffRestrictions() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
    @Test void rejectsInvalidTokens() throws Exception {
        var otherSigner = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                new SecretKeySpec("different-test-key-with-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256")));
        for (String jwt : List.of(
                token("stockflow", "stockflow-web", Instant.now().minusSeconds(120), encoder),
                token("wrong", "stockflow-web", Instant.now().plusSeconds(300), encoder),
                token("stockflow", "wrong", Instant.now().plusSeconds(300), encoder),
                token("stockflow", "stockflow-web", Instant.now().plusSeconds(300), otherSigner))) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isUnauthorized());
        }
    }
    @Test void permissionsAreLoadedFromDatabase() throws Exception {
        String jwt = validToken();
        jdbc.update("UPDATE app_users SET role='ADMIN' WHERE id=?", user.getId());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test void loginThrottlingAndUnknownFields() throws Exception {
        String ip = UUID.randomUUID().toString();
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                    .contentType("application/json").content(body("wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType("application/json").content(body(password)))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"x@example.test\",\"password\":\"test\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }
}
