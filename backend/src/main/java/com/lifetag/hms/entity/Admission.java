package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** An inpatient stay: which bed, which doctor, from when to when. The ward and the per-day rate
 *  are copied onto the admission so the bed-charge line on the invoice stays correct even if the
 *  room's tariff changes later. */
@Entity
@Table(name = "admissions")
@Data
@NoArgsConstructor
public class Admission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /** Attending doctor. */
    @ManyToOne
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    @ManyToOne
    @JoinColumn(name = "room_bed_id")
    private RoomBed roomBed;

    /** General Ward, ICU, Private, ... (snapshot of the room type) */
    @Size(max = 50)
    private String wardType;

    @Size(max = 30)
    private String roomNumber;

    @Size(max = 30)
    private String bedNumber;

    /** Per-day bed charge at the time of admission. */
    @DecimalMin("0.0")
    private BigDecimal chargePerDay = BigDecimal.ZERO;

    private LocalDate admittedOn = LocalDate.now();

    private LocalDate dischargedOn;

    @Size(max = 255)
    private String reason;

    @Size(max = 255)
    private String diagnosis;

    /** Admitted, Discharged */
    @Size(max = 20)
    private String status = "Admitted";

    @Column(length = 3000)
    private String dischargeSummary;

    /** True once the stay has been fully invoiced (set when a discharged stay's invoice is final). */
    private boolean billed = false;

    /** Bed days up to (not including) this date are already on an invoice. Lets a running
     *  inpatient bill keep accruing day by day without ever charging the same night twice. */
    private LocalDate billedUpTo;

    /** Bed days not yet invoiced, up to {@code end}. A stay is always charged at least one day. */
    public long unbilledDays(LocalDate end) {
        if (admittedOn == null) return 1;
        LocalDate from = billedUpTo != null ? billedUpTo : admittedOn;
        long d = ChronoUnit.DAYS.between(from, end);
        if (billedUpTo == null) return Math.max(1, d);
        return Math.max(0, d);
    }

    /** Number of billable days: admission day counts, minimum one. */
    public long getDaysStayed() {
        if (admittedOn == null) return 1;
        LocalDate end = dischargedOn != null ? dischargedOn : LocalDate.now();
        return Math.max(1, ChronoUnit.DAYS.between(admittedOn, end));
    }
}
