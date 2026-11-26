package com.lifetag.hms.security;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory brute-force guard for the unauthenticated password endpoints
 * (login and change-password). After {@value #MAX_FAILURES} consecutive failures
 * for the same username from the same client address, further attempts are
 * refused for {@value #LOCK_MINUTES} minutes. Keyed on username + address so a
 * stranger can't lock a real user out from a different address.
 *
 * State is per-instance and cleared on restart; if the backend is ever scaled to
 * several instances, move this to a shared store (e.g. Redis).
 */
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final int LOCK_MINUTES = 15;
    private static final long LOCK_MS = LOCK_MINUTES * 60_000L;
    private static final int CLEANUP_THRESHOLD = 10_000;

    private static final class Attempt {
        int failures;
        long lockedUntil;
        long lastFailure;
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    private static String key(String scope, String username, String address) {
        return scope + "|" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT)) + "|" + address;
    }

    /** Minutes left on the lock, or 0 if the caller may try. */
    public long minutesLocked(String scope, String username, String address) {
        Attempt a = attempts.get(key(scope, username, address));
        if (a == null) return 0;
        long remaining = a.lockedUntil - System.currentTimeMillis();
        return remaining > 0 ? (remaining + 59_999L) / 60_000L : 0;
    }

    public void recordFailure(String scope, String username, String address) {
        long now = System.currentTimeMillis();
        if (attempts.size() > CLEANUP_THRESHOLD) {
            attempts.entrySet().removeIf(e -> now - e.getValue().lastFailure > LOCK_MS && e.getValue().lockedUntil < now);
        }
        attempts.compute(key(scope, username, address), (k, a) -> {
            if (a == null || (a.lockedUntil < now && now - a.lastFailure > LOCK_MS)) a = new Attempt();
            a.failures++;
            a.lastFailure = now;
            if (a.failures >= MAX_FAILURES) {
                a.lockedUntil = now + LOCK_MS;
                a.failures = 0;
            }
            return a;
        });
    }

    public void recordSuccess(String scope, String username, String address) {
        attempts.remove(key(scope, username, address));
    }
}
