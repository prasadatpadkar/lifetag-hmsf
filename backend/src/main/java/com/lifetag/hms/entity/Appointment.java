package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "appointments")
@Data
@NoArgsConstructor
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    private LocalDate appointmentDate;

    private LocalTime appointmentTime;

    @Size(max = 255, message = "Reason must be at most 255 characters.")
    private String reason;

    /** Scheduled, Completed, Cancelled */
    @Size(max = 30, message = "Status is too long.")
    private String status = "Scheduled";

    @Size(max = 255, message = "Notes must be at most 255 characters.")
    private String notes;

    /** True once the consultation fee has been added to an invoice. */
    private boolean billed = false;
}
