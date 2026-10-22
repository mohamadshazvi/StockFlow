package com.stockflow.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.stockflow.users.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("bootstrap")
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Environment env;
    private final JdbcTemplate jdbc;
    public AdminBootstrap(UserRepository users, PasswordEncoder passwords, Environment env, JdbcTemplate jdbc) {
        this.users = users; this.passwords = passwords; this.env = env; this.jdbc = jdbc;
    }
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbc.execute("SELECT pg_advisory_xact_lock(734091)");
        String email = env.getRequiredProperty("BOOTSTRAP_ADMIN_EMAIL").trim().toLowerCase(Locale.ROOT);
        String password = env.getRequiredProperty("BOOTSTRAP_ADMIN_PASSWORD");
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || bytes < 12 || bytes > 72) {
            throw new IllegalArgumentException("Provide a valid admin email and a password of 12–72 UTF-8 bytes.");
        }
        if (users.count() > 0) throw new IllegalStateException("Bootstrap refused: accounts already exist. Existing passwords are never reset.");
        users.saveAndFlush(new UserAccount(email, passwords.encode(password), "ADMIN"));
        org.slf4j.LoggerFactory.getLogger(AdminBootstrap.class).info("Initial admin account created");
    }
}
