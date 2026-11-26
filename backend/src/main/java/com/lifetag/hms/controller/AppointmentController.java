package com.lifetag.hms.controller;

import com.lifetag.hms.util.Input;
import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.Appointment;
import com.lifetag.hms.entity.Doctor;
import com.lifetag.hms.entity.Patient;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.AppointmentRepository;
import com.lifetag.hms.repository.DoctorRepository;
import com.lifetag.hms.repository.PatientRepository;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private NotificationService notificationService;

    /** Admin/Reception see every appointment. A Patient only ever sees their
     *  own; a Doctor only ever sees theirs — so this list can't be used to
     *  browse another patient's or doctor's bookings. */
    @GetMapping
    public List<Appointment> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return List.of();
        Role role = principal.getUser().getRole();
        if (role == Role.ADMIN || role == Role.RECEPTION) {
            return appointmentRepository.findAll();
        }
        if (role == Role.PATIENT) {
            return patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> appointmentRepository.findByPatientId(p.getId()))
                    .orElse(List.of());
        }
        if (role == Role.DOCTOR) {
            return doctorRepository.findByUserId(principal.getUser().getId())
                    .map(d -> appointmentRepository.findByDoctorId(d.getId()))
                    .orElse(List.of());
        }
        return List.of();
    }

    /** Staff, or the patient / doctor that the appointment belongs to. */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        Appointment appointment = appointmentRepository.findById(id).orElse(null);
        if (appointment == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canModify(appointment, principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own appointments."));
        }
        return ResponseEntity.ok(appointment);
    }

    /** Reception/Admin can look up any patient's appointments; a Patient may
     *  only request their own patientId — otherwise one patient could read
     *  another patient's appointment history just by guessing their id. */
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<?> byPatient(@PathVariable Long patientId, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal != null && principal.getUser().getRole() == Role.PATIENT) {
            boolean isOwnRecord = patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> p.getId().equals(patientId)).orElse(false);
            if (!isOwnRecord) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own appointments."));
            }
        }
        return ResponseEntity.ok(appointmentRepository.findByPatientId(patientId));
    }

    /** Same idea for a Doctor requesting a doctorId — must be their own. */
    @GetMapping("/doctor/{doctorId}")
    public ResponseEntity<?> byDoctor(@PathVariable Long doctorId, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal != null && principal.getUser().getRole() == Role.DOCTOR) {
            boolean isOwnRecord = doctorRepository.findByUserId(principal.getUser().getId())
                    .map(d -> d.getId().equals(doctorId)).orElse(false);
            if (!isOwnRecord) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own appointments."));
            }
        }
        return ResponseEntity.ok(appointmentRepository.findByDoctorId(doctorId));
    }

    /** Body: { "patientId": 1, "doctorId": 2, "appointmentDate": "2026-08-25",
                "appointmentTime": "10:30", "reason": "Checkup" }
     *  A Patient booking their own appointment always gets patientId forced to
     *  their own record — a request body claiming a different patientId is
     *  ignored rather than trusted, so one patient can't book (or spam) an
     *  appointment under another patient's name. */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        Long patientId;
        if (principal != null && principal.getUser().getRole() == Role.PATIENT) {
            Patient own = patientRepository.findByUserId(principal.getUser().getId()).orElse(null);
            if (own == null) {
                return ResponseEntity.badRequest().body(new ApiResponse(false, "No patient profile is linked to this account yet."));
            }
            patientId = own.getId();
        } else {
            if (body.get("patientId") == null) {
                return ResponseEntity.badRequest().body(new ApiResponse(false, "patientId is required."));
            }
            patientId = Input.id(body, "patientId");
        }

        Patient patient = patientRepository.findById(patientId).orElse(null);
        Doctor doctor = body.get("doctorId") != null
                ? doctorRepository.findById(Input.id(body, "doctorId")).orElse(null)
                : null;
        if (patient == null || doctor == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient or doctor id"));
        }

        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        if (body.get("appointmentDate") != null) {
            appointment.setAppointmentDate(java.time.LocalDate.parse(body.get("appointmentDate").toString()));
        }
        if (body.get("appointmentTime") != null) {
            appointment.setAppointmentTime(java.time.LocalTime.parse(body.get("appointmentTime").toString()));
        }
        if (body.get("reason") != null) {
            appointment.setReason(body.get("reason").toString());
        }
        if (body.get("status") != null) {
            appointment.setStatus(body.get("status").toString());
        }
        Appointment saved = appointmentRepository.save(appointment);

        String when = (saved.getAppointmentDate() != null ? saved.getAppointmentDate().toString() : "TBD")
                + (saved.getAppointmentTime() != null ? " at " + saved.getAppointmentTime() : "");
        notificationService.notifyUser(patient.getUser(), "Appointment booked",
                "Your appointment with " + doctor.getName() + " on " + when + " has been booked.",
                "APPOINTMENT", "patient-appointments.html");
        notificationService.notifyUser(doctor.getUser(), "New appointment",
                patient.getName() + " booked an appointment with you on " + when + ".",
                "APPOINTMENT", "doctor-appointments.html");
        notificationService.notifyRole(Role.RECEPTION, "New appointment booked",
                patient.getName() + " booked an appointment with " + doctor.getName() + " on " + when + ".",
                "APPOINTMENT", "reception-appointments.html");

        return ResponseEntity.ok(saved);
    }

    /** A Patient may only update their own appointment; a Doctor only theirs.
     *  Admin/Reception can update any. */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody Appointment updated,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        Appointment existing = appointmentRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canModify(existing, principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "You can only update your own appointments."));
        }

        if (updated.getAppointmentDate() != null) existing.setAppointmentDate(updated.getAppointmentDate());
        if (updated.getAppointmentTime() != null) existing.setAppointmentTime(updated.getAppointmentTime());
        if (updated.getReason() != null) existing.setReason(updated.getReason());
        boolean statusChanged = updated.getStatus() != null && !updated.getStatus().equals(existing.getStatus());
        if (updated.getStatus() != null) existing.setStatus(updated.getStatus());
        if (updated.getNotes() != null) existing.setNotes(updated.getNotes());
        Appointment saved = appointmentRepository.save(existing);
        if (statusChanged && saved.getPatient() != null) {
            notificationService.notifyUser(saved.getPatient().getUser(), "Appointment " + saved.getStatus().toLowerCase(),
                    "Your appointment with " + (saved.getDoctor() != null ? saved.getDoctor().getName() : "your doctor")
                            + " is now " + saved.getStatus() + ".",
                    "APPOINTMENT", "patient-appointments.html");
        }
        return ResponseEntity.ok(saved);
    }

    /** Only Admin/Reception hard-delete an appointment record; Patients cancel
     *  via a status update (PUT) instead, which keeps the record as history. */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || (principal.getUser().getRole() != Role.ADMIN && principal.getUser().getRole() != Role.RECEPTION)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can remove an appointment record."));
        }
        if (!appointmentRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        appointmentRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private boolean canModify(Appointment appointment, UserPrincipal principal) {
        if (principal == null) return false;
        Role role = principal.getUser().getRole();
        if (role == Role.ADMIN || role == Role.RECEPTION) return true;
        if (role == Role.PATIENT) {
            return patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> appointment.getPatient() != null && p.getId().equals(appointment.getPatient().getId()))
                    .orElse(false);
        }
        if (role == Role.DOCTOR) {
            return doctorRepository.findByUserId(principal.getUser().getId())
                    .map(d -> appointment.getDoctor() != null && d.getId().equals(appointment.getDoctor().getId()))
                    .orElse(false);
        }
        return false;
    }
}
