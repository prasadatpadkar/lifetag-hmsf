package com.lifetag.hms.service;

import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.AdmissionRepository;
import com.lifetag.hms.repository.AppointmentRepository;
import com.lifetag.hms.repository.RoomBedRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * The single place where a patient is admitted to a bed, moved, or discharged - used by the
 * Admissions screens AND the Room & Bed screen, so a bed can never be occupied without an
 * admission behind it (which is what feeds the room charges into billing).
 */
@Service
public class AdmissionService {

    public static final String ADMITTED = "Admitted";
    public static final String DISCHARGED = "Discharged";

    private final AdmissionRepository admissions;
    private final RoomBedRepository beds;
    private final AppointmentRepository appointments;
    private final AdmissionBillingService admissionBilling;
    private final NotificationService notifications;

    public AdmissionService(AdmissionRepository admissions, RoomBedRepository beds, AppointmentRepository appointments,
                            AdmissionBillingService admissionBilling, NotificationService notifications) {
        this.admissions = admissions;
        this.beds = beds;
        this.appointments = appointments;
        this.admissionBilling = admissionBilling;
        this.notifications = notifications;
    }

    public Admission activeAdmission(Long patientId) {
        List<Admission> list = admissions.findByPatientIdAndStatus(patientId, ADMITTED);
        return list.isEmpty() ? null : list.get(0);
    }

    /** The doctor the patient last saw, used when a bed is allotted without naming a doctor. */
    private Doctor lastDoctor(Long patientId) {
        return appointments.findByPatientId(patientId).stream()
                .filter(a -> a.getDoctor() != null && a.getAppointmentDate() != null
                        && !a.getAppointmentDate().isAfter(LocalDate.now()) && !"Cancelled".equalsIgnoreCase(a.getStatus()))
                .max(Comparator.comparing(Appointment::getAppointmentDate))
                .map(Appointment::getDoctor).orElse(null);
    }

    /** Admit a patient (optionally into a bed) and open the running inpatient invoice. */
    @Transactional
    public Admission admit(Patient patient, Doctor doctor, RoomBed bed, String reason, String diagnosis, LocalDate admittedOn) {
        Admission existing = activeAdmission(patient.getId());
        if (existing != null) {
            throw new IllegalArgumentException(patient.getName() + " is already admitted"
                    + (existing.getBedNumber() != null ? " (bed " + existing.getBedNumber() + ")" : "")
                    + ". Discharge or transfer the patient first.");
        }
        LocalDate on = admittedOn == null ? LocalDate.now() : admittedOn;
        if (on.isAfter(LocalDate.now())) throw new IllegalArgumentException("Admission date cannot be in the future.");

        Admission a = new Admission();
        a.setPatient(patient);
        a.setDoctor(doctor != null ? doctor : lastDoctor(patient.getId()));
        a.setReason(reason);
        a.setDiagnosis(diagnosis);
        a.setAdmittedOn(on);
        a.setStatus(ADMITTED);
        if (bed != null) {
            if ("Occupied".equalsIgnoreCase(bed.getStatus())) throw new IllegalArgumentException("That bed is already occupied.");
            if (!"Available".equalsIgnoreCase(bed.getStatus())) {
                throw new IllegalArgumentException("That bed is under " + bed.getStatus().toLowerCase() + " and cannot be allotted.");
            }
            a.setRoomBed(bed);
            a.setWardType(bed.getRoomType());
            a.setRoomNumber(bed.getRoomNumber());
            a.setBedNumber(bed.getBedNumber());
            a.setChargePerDay(bed.getChargePerDay() == null ? BigDecimal.ZERO : BigDecimal.valueOf(bed.getChargePerDay()));
            bed.setStatus("Occupied");
            bed.setPatient(patient);
            beds.save(bed);
        }
        Admission saved = admissions.save(a);
        admissionBilling.sync(saved, true);
        return saved;
    }

    /** Discharge: free the bed, stop the bed-day meter and finalise the invoice. */
    @Transactional
    public Admission discharge(Admission a, LocalDate on, String summary, String diagnosis, boolean notifyPatient) {
        if (DISCHARGED.equalsIgnoreCase(a.getStatus())) throw new IllegalArgumentException("This patient has already been discharged.");
        LocalDate when = on == null ? LocalDate.now() : on;
        if (a.getAdmittedOn() != null && when.isBefore(a.getAdmittedOn())) {
            throw new IllegalArgumentException("Discharge date cannot be before the admission date.");
        }
        if (when.isAfter(LocalDate.now())) throw new IllegalArgumentException("Discharge date cannot be in the future.");
        a.setDischargedOn(when);
        a.setStatus(DISCHARGED);
        if (summary != null) a.setDischargeSummary(summary);
        if (diagnosis != null) a.setDiagnosis(diagnosis);
        if (a.getRoomBed() != null) {
            RoomBed bed = a.getRoomBed();
            bed.setStatus("Available");
            bed.setPatient(null);
            beds.save(bed);
        }
        Admission saved = admissions.save(a);
        admissionBilling.finalizeOnDischarge(saved);
        if (notifyPatient) {
            notifications.notifyUser(saved.getPatient().getUser(), "Discharge completed",
                    "You were discharged on " + when + ". Your discharge summary is in your medical record.", "PATIENT", "patient-records.html");
        }
        return saved;
    }

    /** Move an admitted patient to another bed: the old stay is closed (and billed at its own tariff)
     *  and a new stay opens in the new bed. */
    @Transactional
    public Admission transfer(Admission current, RoomBed newBed) {
        if (current.getRoomBed() != null && current.getRoomBed().getId().equals(newBed.getId())) return current;
        if (!"Available".equalsIgnoreCase(newBed.getStatus())) throw new IllegalArgumentException("That bed is not available.");
        Patient patient = current.getPatient();
        Doctor doctor = current.getDoctor();
        String reason = current.getReason();
        String diagnosis = current.getDiagnosis();
        String from = current.getBedNumber() == null ? "previous bed" : "bed " + current.getBedNumber();
        discharge(current, LocalDate.now(), "Transferred from " + from + " to bed " + newBed.getBedNumber() + ".", null, false);
        return admit(patient, doctor, newBed, reason, diagnosis, LocalDate.now());
    }
}
