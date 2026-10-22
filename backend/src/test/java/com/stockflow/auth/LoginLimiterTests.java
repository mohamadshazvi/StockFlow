package com.stockflow.auth;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoginLimiterTests {
    @Test void limitsBothIpAndAccount() {
        var limiter = new LoginLimiter(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        for (int i = 0; i < 10; i++) assertThat(limiter.allow("ip1", "account1")).isTrue();
        assertThat(limiter.allow("ip1", "other-account")).isFalse();
        assertThat(limiter.allow("other-ip", "account1")).isFalse();
        assertThat(limiter.allow("other-ip", "other-account")).isTrue();
    }
}
