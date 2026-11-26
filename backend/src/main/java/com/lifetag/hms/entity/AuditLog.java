package com.lifetag.hms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Append-only record of every INSERT / UPDATE / DELETE on a business table.
 * Rows are written automatically by {@link com.lifetag.hms.config.AuditEventListener};
 * there is deliberately no setter and no update/delete endpoint.
 * old_values / new_values hold JSON; password fields are never included.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_user", columnList = "user_id"),
        @Index(name = "idx_audit_record", columnList = "table_name, record_id"),
        @Index(name = "idx_audit_created", columnList = "created_at")
})
@Getter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_name", nullable = false, length = 64)
    private String tableName;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    /** INSERT, UPDATE or DELETE */
    @Column(nullable = false, length = 10)
    private String operation;

    /** Acting user's id; null for system actions (startup seeding, anonymous sign-up). */
    @Column(name = "user_id")
    private Long userId;

    @Column(length = 100)
    private String username;

    @Column(name = "old_values", columnDefinition = "TEXT")
    private String oldValues;

    @Column(name = "new_values", columnDefinition = "TEXT")
    private String newValues;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
