package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "insurance")
@Data
@NoArgsConstructor
public class Insurance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Size(max = 100, message = "Provider must be at most 100 characters.")
    private String provider;

    @Size(max = 50, message = "Policy number must be at most 50 characters.")
    private String policyNumber;

    @DecimalMin(value = "0.0", message = "Coverage amount cannot be negative.")
    @DecimalMax(value = "99999999.99", message = "Coverage amount is too large.")
    private BigDecimal coverageAmount;

    /** Individual, Family Floater, Group / Corporate */
    @Size(max = 50, message = "Coverage type must be at most 50 characters.")
    private String coverageType;

    private LocalDate validFrom;

    private LocalDate validTo;

    /** Active, Expired, Claimed, Pending */
    @Size(max = 30, message = "Status must be at most 30 characters.")
    private String status = "Active";

    /** Sum already sanctioned against the policy (filled in on read, never stored). */
    @Transient
    private BigDecimal utilisedAmount;

    /** Sum insured still available: coverageAmount - utilisedAmount (filled in on read). */
    @Transient
    private BigDecimal remainingAmount;
}
