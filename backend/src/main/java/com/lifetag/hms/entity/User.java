package com.lifetag.hms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** Bcrypt hash — never serialized out. This entity is returned directly (not
     *  via a DTO) from several endpoints, e.g. GET /api/auth/me and the nested
     *  `user` on GET /api/doctors and /api/patients, so without @JsonIgnore this
     *  hash would leak to any authenticated caller. */
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, unique = true)
    private String email;

    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private boolean enabled = true;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** Tokens issued before this moment (epoch millis) are rejected. Set on logout and on
     *  password change so a stolen or old token stops working immediately. */
    @JsonIgnore
    @Column(name = "tokens_valid_after_ms")
    private Long tokensValidAfterMs;

    public User(String username, String password, String email, String fullName, Role role) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
    }
}
