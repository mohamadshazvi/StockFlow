package com.stockflow.users;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import jakarta.validation.constraints.*;
import com.stockflow.common.*;
import com.stockflow.common.error.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {
    @io.swagger.v3.oas.annotations.media.Schema(name = "CreateUser")
    public record Create(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password,
            @NotNull @Pattern(regexp = "ADMIN|STAFF") String role) {
        public Create { email = email == null ? null : email.trim().toLowerCase(Locale.ROOT); }
        @Override public String toString() { return "Create[email=REDACTED,password=REDACTED,role=" + role + "]"; }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "UserAccountView")
    public record View(UUID id, String email, String role, boolean active, Instant createdAt, Instant updatedAt) {
        static View of(UserAccount u) { return new View(u.getId(), u.getEmail(), u.getRole(), u.isActive(), u.getCreatedAt(), u.getUpdatedAt()); }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "UserAccountPage")
    public record Page(List<View> content, int page, int size, long totalElements, int totalPages) {}
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    public UserService(UserRepository users, PasswordEncoder passwords, JdbcTemplate jdbc) { this.users = users; this.passwords = passwords; this.jdbc = jdbc; }
    public Page list(int page, int size, String sort) {
        var result = users.findAll(ListQuery.page(page, size, sort, Set.of("email", "createdAt")));
        return new Page(result.getContent().stream().map(View::of).toList(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
    @Transactional
    public View create(Create input) {
        int bytes = input.password().getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 12 || bytes > 72) throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Password must be 12–72 UTF-8 bytes.");
        if (users.findByEmail(input.email()).isPresent()) throw new ApiProblem(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "This email is already in use.");
        return View.of(users.saveAndFlush(new UserAccount(input.email(), passwords.encode(input.password()), input.role())));
    }
    @Transactional(timeout = 10)
    public View patch(UUID id, Map<String, Object> patch) {
        PatchInput.check(patch, "role", "active");
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        // Same lock key as first-admin bootstrap; the count occurs only after acquiring it.
        jdbc.execute("SELECT pg_advisory_xact_lock(734091)");
        UserAccount user = users.findById(id).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found."));
        String role = PatchInput.text(patch, "role", user.getRole(), false);
        if (!Set.of("ADMIN", "STAFF").contains(role)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Role must be ADMIN or STAFF.");
        boolean active = PatchInput.bool(patch, "active", user.isActive());
        if (user.isActive() && user.getRole().equals("ADMIN") && (!active || !role.equals("ADMIN"))) {
            Long admins = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE active AND role='ADMIN'", Long.class);
            if (admins == null || admins <= 1) throw new ApiProblem(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED", "At least one active administrator must remain.");
        }
        user.updateAccess(role, active); users.flush(); return View.of(user);
    }
}
