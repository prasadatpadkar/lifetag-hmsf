package com.lifetag.hms.util;

import com.lifetag.hms.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Generates a login username and a temporary password (random temporary password) for
 * accounts that Admin/Reception create on someone else's behalf — doctors,
 * receptionists, other admins, and walk-in patients. The new person signs in once
 * with these, then uses "Change password" on the login screen to set a password
 * only they know. Lifetag never stores the plaintext temp password anywhere; it is
 * only ever handed back once, in the API response right after creation.
 */
@Component
public class CredentialGenerator {

    private final UserRepository userRepository;

    public CredentialGenerator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** e.g. "Dr. Sarah Johnson" -&gt; "sarah.johnson" (numeric suffix appended if already taken). */
    public String generateUsername(String fullName) {
        String base = normalize(fullName).replace(' ', '.');
        if (base.isBlank()) base = "user";
        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsername(candidate)) {
            suffix++;
            candidate = base + suffix;
        }
        return candidate;
    }

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** A random 12-character temporary password (no look-alike characters such as
     *  0/O or 1/l/I, so it can be read out or copied by hand). The name is no longer
     *  used - a password derived from someone's name is guessable by anyone who knows
     *  who was just hired. */
    public String generateTempPassword(String fullName) {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    private String normalize(String fullName) {
        String cleaned = fullName == null ? "" : fullName.replaceAll("(?i)^dr\\.?\\s*", "").trim();
        cleaned = cleaned.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", "").trim();
        return cleaned.replaceAll("\\s+", " ");
    }
}
