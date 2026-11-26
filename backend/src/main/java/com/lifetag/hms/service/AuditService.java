package com.lifetag.hms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/** Records security events (LOGIN, LOGOUT, LOGIN_FAIL) in audit_log. No passwords or typed-in names are stored. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param userId acting/affected user id, or null when the account is unknown (stored as record 0). */
    public void event(String operation, Long userId, String username) {
        try {
            jdbc.update("INSERT INTO audit_log (table_name, record_id, operation, user_id, username, old_values, new_values, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)",
                    "users", userId == null ? 0L : userId, operation, userId, username, Timestamp.valueOf(LocalDateTime.now()));
        } catch (Exception ex) {
            log.warn("Could not write {} audit event: {}", operation, ex.getMessage());
        }
    }
}
