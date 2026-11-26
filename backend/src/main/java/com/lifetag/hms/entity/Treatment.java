package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A course of treatment given to a patient: the diagnosis, what was done (medication, surgery,
 *  therapy...), the medicines prescribed and how it turned out. Gives a doctor the patient's
 *  "what have they already been treated with" history at a glance. */
@Entity
@Table(name = "treatments")
@Data
@NoArgsConstructor
public class Treatment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    /** Set when the treatment happened during an inpatient stay. */
    private Long admissionId;

    @NotBlank
    @Size(max = 200)
    private String diagnosis;

    /** ICD-10 style code, e.g. I10 (hypertension). */
    @Size(max = 20)
    private String diagnosisCode;

    /** Medication, Surgery, Procedure, Therapy, Counselling, Observation */
    @Size(max = 40)
    private String treatmentType = "Medication";

    /** Name of the procedure/surgery, if any. */
    @Size(max = 200)
    private String procedureName;

    @Column(length = 3000)
    private String plan;

    /** JSON array of {name, dose, frequency, duration}. */
    @Column(length = 4000)
    private String medicationsJson;

    private LocalDate startDate = LocalDate.now();

    private LocalDate endDate;

    /** Ongoing, Completed, Discontinued */
    @Size(max = 20)
    private String status = "Ongoing";

    @Size(max = 500)
    private String outcome;

    private LocalDate followUpDate;

    @Size(max = 500)
    private String notes;

    /** Professional / procedure charge, for billing. */
    @DecimalMin("0.0")
    private BigDecimal procedureCost = BigDecimal.ZERO;

    /** Pharmacy cost of the medicines prescribed, for billing. */
    @DecimalMin("0.0")
    private BigDecimal medicationCost = BigDecimal.ZERO;

    private boolean billed = false;
}
