package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One set of vital-sign readings (BP, pulse, SpO2, temperature, sugar, weight...) taken for a
 *  patient at a point in time. A patient accumulates many of these, which is what powers the
 *  BP / sugar / weight trend charts on the patient record page. */
@Entity
@Table(name = "vitals")
@Data
@NoArgsConstructor
public class Vitals {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    private LocalDateTime recordedAt = LocalDateTime.now();

    /** Name of the doctor / nurse who took the reading. */
    @Size(max = 100)
    private String recordedBy;

    @Min(40) @Max(300)
    private Integer systolic;

    @Min(20) @Max(200)
    private Integer diastolic;

    @Min(20) @Max(250)
    private Integer pulse;

    /** Body temperature in degrees Celsius. */
    @DecimalMin("30.0") @DecimalMax("45.0")
    private Double temperatureC;

    /** Oxygen saturation, percent. */
    @Min(30) @Max(100)
    private Integer spo2;

    @Min(4) @Max(80)
    private Integer respiratoryRate;

    @DecimalMin("0.5") @DecimalMax("500.0")
    private Double weightKg;

    @DecimalMin("20.0") @DecimalMax("260.0")
    private Double heightCm;

    /** Blood glucose in mg/dL. */
    @Min(10) @Max(1500)
    private Integer bloodSugar;

    /** Fasting, Post-meal, Random */
    @Size(max = 30)
    private String sugarType;

    @Size(max = 500)
    private String notes;
}
