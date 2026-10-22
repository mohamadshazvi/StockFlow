package com.stockflow.auth;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Single-instance fixed-window limiter. Never trusts client-supplied forwarded headers. */
@Component
public class LoginLimiter {
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(long minute, int count) {}
    public LoginLimiter() { this(Clock.systemUTC()); }
    LoginLimiter(Clock clock) { this.clock = clock; }
    public synchronized boolean allow(String ip, String email) {
        long minute = clock.millis() / 60_000;
        windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        String ipKey = "ip:" + ip, userKey = "email:" + email;
        if ((!windows.containsKey(ipKey) || !windows.containsKey(userKey)) && windows.size() >= 10_000) return false;
        boolean allowed = true;
        for (String key : new String[]{ipKey, userKey}) {
            Window previous = windows.getOrDefault(key, new Window(minute, 0));
            windows.put(key, new Window(minute, Math.min(11, previous.count() + 1)));
            if (previous.count() >= 10) allowed = false;
        }
        return allowed;
    }
}
