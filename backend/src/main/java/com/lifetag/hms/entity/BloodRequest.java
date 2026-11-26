package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "blood_requests")
@Data
@NoArgsConstructor
public class BloodRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(nullable = false)
    @Size(max = 10, message = "Blood group is too long.")
    private String bloodGroup;

    @Min(value = 1, message = "Units must be at least 1.")
    @Max(value = 1000, message = "Units value is too large.")
    private Integer units = 1;

    @Size(max = 50, message = "Ward is too long.")
    private String ward;

    /** Requested, Urgent, Fulfilled, Cancelled */
    private String status = "Requested";

    private LocalDate requestDate = LocalDate.now();

    /** Who raised the request (doctor / reception name) - shown on the blood bank screen. */
    @Size(max = 100)
    private String requestedBy;

    /** Inpatient stay the blood was issued during, if any (so it lands on the running admission bill). */
    @ManyToOne
    @JoinColumn(name = "admission_id")
    private Admission admission;

    /** Day the units were issued from stock (status became Fulfilled). */
    private LocalDate fulfilledDate;

    /** Hospital charge per unit at the time of issue (snapshot of BloodBank.pricePerUnit). */
    @DecimalMin("0.0")
    private BigDecimal pricePerUnit = BigDecimal.ZERO;

    /** Units x price - what the patient is charged for this request. */
    @DecimalMin("0.0")
    private BigDecimal totalCharge = BigDecimal.ZERO;

    /** True once the charge is on an invoice. */
    private boolean billed = false;
}
