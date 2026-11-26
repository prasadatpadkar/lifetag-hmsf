package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

@Entity
@Table(name = "scheme_claims")
@Data
@NoArgsConstructor
public class SchemeClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "scheme_id", nullable = false)
    private Scheme scheme;

    /** The verified enrollment this claim was filed against. */
    @ManyToOne
    @JoinColumn(name = "patient_scheme_id")
    private PatientScheme enrollment;

    @Size(max = 50, message = "Invoice reference must be at most 50 characters.")
    private String invoiceRef;

    @Size(max = 50, message = "Package code must be at most 50 characters.")
    private String packageCode;

    @Size(max = 150, message = "Package name must be at most 150 characters.")
    private String packageName;

    @DecimalMin(value = "0.0", message = "Claimed amount cannot be negative.")
    @DecimalMax(value = "99999999.99", message = "Claimed amount is too large.")
    private BigDecimal claimedAmount;

    @DecimalMin(value = "0.0", message = "Sanctioned amount cannot be negative.")
    @DecimalMax(value = "99999999.99", message = "Sanctioned amount is too large.")
    private BigDecimal sanctionedAmount = BigDecimal.ZERO;

    /** The only values the API accepts for {@link #status}. */
    public static final Set<String> STATUSES = Set.of("Draft", "Claim Submitted", "Under Govt Review", "Approved", "Rejected");

    /** Draft, Claim Submitted, Under Govt Review, Approved, Rejected */
    @Size(max = 30, message = "Status must be at most 30 characters.")
    private String status = "Draft";

    /** There is no live government-portal integration in this build (see
     *  schemes-reception.js on the frontend for exactly what "submit" does and
     *  doesn't do) — this just records whatever reference the submission step
     *  produced: a labeled SANDBOX number, or later, a real one an admin
     *  types in after filing on the actual portal and getting one back. */
    @Size(max = 60, message = "Reference number must be at most 60 characters.")
    private String govtReferenceNumber;

    /** SIMULATED or GOVERNMENT */
    @Size(max = 30, message = "Submission mode must be at most 30 characters.")
    private String submissionMode;

    private LocalDate submittedDate;

    private LocalDate responseDate;

    @Size(max = 2000, message = "Remarks must be at most 2000 characters.")
    @Column(length = 2000)
    private String remarks;

    /** JSON array of {at, event, by} — same free-form-JSON-column pattern as
     *  Billing.itemsJson. Kept as text rather than a child table since it's
     *  purely an append-only display log, never queried by field. */
    @Column(length = 4000)
    private String auditTrailJson;
}
