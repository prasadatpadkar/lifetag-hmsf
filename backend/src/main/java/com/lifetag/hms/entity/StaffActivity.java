package com.lifetag.hms.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "staff_activity")
@Data
@NoArgsConstructor
public class StaffActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String staffName;

    private String role;

    private String action;

    @Column(length = 1000)
    private String details;

    private LocalDateTime timestamp = LocalDateTime.now();
}
