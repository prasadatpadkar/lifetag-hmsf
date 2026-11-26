package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A diagnostic investigation for a patient: imaging (MRI, CT, X-Ray, Ultrasound), cardiac tests
 *  (ECG, Echo, TMT) or laboratory work (blood, urine, biopsy...). Lab reports can carry structured
 *  values in {@link #resultsJson} so the UI can flag out-of-range results. */
@Entity
@Table(name = "diagnostic_reports")
@Data
@NoArgsConstructor
public class DiagnosticReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /** The doctor who ordered the investigation. */
    @ManyToOne
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    /** Radiology, Laboratory, Cardiac, Pathology */
    @Size(max = 40)
    private String category;

    /** MRI, CT Scan, X-Ray, Ultrasound, ECG, Echo, Blood Test, Urine Test, Biopsy ... */
    @NotBlank
    @Size(max = 60)
    private String reportType;

    /** e.g. "MRI Brain with contrast", "Complete Blood Count" */
    @Size(max = 150)
    private String title;

    private LocalDate reportDate = LocalDate.now();

    @Column(length = 3000)
    private String findings;

    @Column(length = 1500)
    private String impression;

    /** Normal, Abnormal, Critical */
    @Size(max = 20)
    private String resultStatus = "Normal";

    /** Ordered, Completed, Reviewed */
    @Size(max = 20)
    private String status = "Completed";

    /** Radiologist / pathologist who signed the report. */
    @Size(max = 100)
    private String performedBy;

    @Size(max = 100)
    private String labName;

    /** JSON array of {test, value, unit, range, flag} for lab-style reports. */
    @Column(length = 4000)
    private String resultsJson;

    /** Optional link to the scanned report / image in an external store. */
    @Size(max = 255)
    private String reportUrl;

    /** Charge for this investigation; used to build the invoice. */
    @DecimalMin("0.0")
    private BigDecimal cost = BigDecimal.ZERO;

    /** True once the charge has been added to an invoice. */
    private boolean billed = false;
}
