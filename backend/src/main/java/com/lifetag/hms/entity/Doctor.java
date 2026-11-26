package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "doctors")
@Data
@NoArgsConstructor
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required.")
    @Size(max = 100, message = "Name must be at most 100 characters.")
    @Column(nullable = false)
    private String name;

    @Size(max = 100, message = "Specialization is too long.")
    private String specialization;

    @Size(max = 100, message = "Department is too long.")
    private String department;

    @Pattern(regexp = "^[0-9+()\\- ]{0,20}$", message = "Contact may contain only digits, spaces, + ( ) - and be at most 20 characters.")
    private String contact;

    @Email(message = "Enter a valid email address.")
    @Size(max = 120, message = "Email must be at most 120 characters.")
    private String email;

    @Size(max = 150, message = "Qualification is too long.")
    private String qualification;

    @Min(value = 0, message = "Experience must be 0 or more.")
    @Max(value = 80, message = "Experience must be 80 or less.")
    private Integer experienceYears;

    /** Standard OPD consultation fee in rupees; used when generating invoices. */
    @DecimalMin(value = "0.0", message = "Consultation fee cannot be negative.")
    private BigDecimal consultationFee;

    @Size(max = 100, message = "Available days is too long.")
    private String availableDays;

    @Size(max = 30, message = "Status is too long.")
    private String status = "Active";

    @OneToOne
    @JoinColumn(name = "user_id")
    private User user;

    /** Populated only in the response right after Admin creates this doctor, so the
     *  UI can show the one-time login to hand over. Never persisted, never re-served
     *  by a GET afterwards. */
    @Transient
    private String tempUsername;

    @Transient
    private String tempPassword;
}
