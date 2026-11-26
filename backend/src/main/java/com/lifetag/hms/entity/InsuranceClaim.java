package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

@Entity
@Table(name = "insurance_claims")
@Data
@NoArgsConstructor
public class InsuranceClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "insurance_id", nullable = false)
    private Insurance insurance;

    @Size(max = 50, message = "Invoice reference must be at most 50 characters.")
    private String invoiceRef;

    @DecimalMin(value = "0.0", message = "Claim amount cannot be negative.")
    @DecimalMax(value = "99999999.99", message = "Claim amount is too large.")
    private BigDecimal claimAmount;

    @DecimalMin(value = "0.0", message = "Approved amount cannot be negative.")
    @DecimalMax(value = "99999999.99", message = "Approved amount is too large.")
    private BigDecimal approvedAmount = BigDecimal.ZERO;

    /** The only values the API accepts for {@link #status}. */
    public static final Set<String> STATUSES = Set.of("Submitted", "Under Review", "Approved", "Rejected");

    /** Submitted, Under Review, Approved, Rejected */
    @Size(max = 30, message = "Status must be at most 30 characters.")
    private String status = "Submitted";

    private LocalDate submittedDate = LocalDate.now();

    private LocalDate responseDate;

    @Size(max = 2000, message = "Remarks must be at most 2000 characters.")
    @Column(length = 2000)
    private String remarks;
}
