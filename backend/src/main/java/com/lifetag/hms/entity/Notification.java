package com.lifetag.hms.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Recipient. Every notification belongs to exactly one login account. */
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String message;

    /** Category used to pick an icon on the frontend: APPOINTMENT, BILLING, SCHEME,
     *  FEEDBACK, BLOOD, PATIENT, SYSTEM. Not enforced server-side so new categories
     *  can be added without a migration. */
    private String type = "SYSTEM";

    /** Optional relative frontend link (e.g. "patient-appointments.html") the bell
     *  dropdown navigates to when this notification is clicked. */
    private String link;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Notification(User user, String title, String message, String type, String link) {
        this.user = user;
        this.title = title;
        this.message = message;
        if (type != null) this.type = type;
        this.link = link;
    }
}
