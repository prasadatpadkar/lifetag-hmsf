package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "insurance_providers")
@Data
@NoArgsConstructor
public class InsuranceProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required.")
    @Size(max = 150, message = "Name must be at most 150 characters.")
    @Column(nullable = false)
    private String name;

    @Size(max = 100, message = "Contact is too long.")
    private String contact;

    private boolean active = true;
}
