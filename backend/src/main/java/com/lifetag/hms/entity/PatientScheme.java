package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "patient_schemes")
@Data
@NoArgsConstructor
public class PatientScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "scheme_id", nullable = false)
    private Scheme scheme;

    private LocalDate enrollmentDate = LocalDate.now();

    /** Pending Verification, Verified, Rejected */
    @Size(max = 40, message = "Status must be at most 40 characters.")
    private String status = "Pending Verification";

    /** Regulated, sensitive government ID data — stored here (as a real backend
     *  would, ideally encrypted at rest) but the API/UI must only ever display
     *  the masked form; see maskAadhaar() on the frontend. */
    @Pattern(regexp = "^[0-9]{0,12}$", message = "Aadhaar number must be 12 digits.")
    private String aadhaarNumber;

    @Size(max = 50, message = "Beneficiary card ID must be at most 50 characters.")
    private String beneficiaryCardId;

    /** Who verified eligibility, and when — set only when status becomes Verified. */
    @Size(max = 100, message = "Verified by must be at most 100 characters.")
    private String verifiedBy;

    private LocalDate verifiedDate;
}
