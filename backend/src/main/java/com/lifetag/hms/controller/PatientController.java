package com.lifetag.hms.controller;

import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.Patient;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.entity.User;
import com.lifetag.hms.repository.PatientRepository;
import com.lifetag.hms.repository.UserRepository;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.NotificationService;
import com.lifetag.hms.util.CredentialGenerator;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    @Autowired
    private com.lifetag.hms.repository.AdmissionRepository admissionRepository;
    @Autowired
    private com.lifetag.hms.repository.BillingRepository billingRepository;


    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private com.lifetag.hms.repository.AppointmentRepository appointmentRepository;
    @Autowired
    private com.lifetag.hms.repository.DoctorRepository doctorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CredentialGenerator credentialGenerator;
    @Autowired
    private NotificationService notificationService;

    private boolean isStaff(UserPrincipal principal) {
        return principal != null
                && (principal.getUser().getRole() == Role.ADMIN || principal.getUser().getRole() == Role.RECEPTION);
    }

    /** The full patient directory (contact details, addresses, blood groups) is
     *  back-office data: Admin/Reception only. Patients and Doctors get their own
     *  records via /me and the appointments they are part of. */
    @GetMapping
    public ResponseEntity<?> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can browse the patient directory."));
        }
        return ResponseEntity.ok(patientRepository.findAll());
    }

    /** Staff can open any patient; a patient only their own record; a doctor only the patients
     *  who have booked with that doctor. */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(new ApiResponse(false, "Sign in required."));
        }
        Role role = principal.getUser().getRole();
        if (role == Role.PATIENT) {
            boolean own = patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> p.getId().equals(id)).orElse(false);
            if (!own) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own record."));
            }
        }
        if (role == Role.DOCTOR) {
            boolean linked = doctorRepository.findByUserId(principal.getUser().getId())
                    .map(d -> appointmentRepository.existsByDoctorIdAndPatientId(d.getId(), id)).orElse(false);
            if (!linked) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view patients who have booked with you."));
            }
        }
        return patientRepository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Resolves the Patient profile for the currently signed-in account — this is
     *  how the patient portal reliably knows "who am I" (their real patient id,
     *  name, doctor list access, etc.) from the JWT session, instead of the old
     *  approach of guessing from whatever name was typed on the login screen. */
    @GetMapping("/me")
    public ResponseEntity<?> getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.getUser().getRole() != Role.PATIENT) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only a patient account can access this."));
        }
        return patientRepository.findByUserId(principal.getUser().getId())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(new ApiResponse(false, "No patient profile is linked to this account yet — contact Reception.")));
    }

    /** Admin/Reception registers a walk-in patient. This also creates their login
     *  (role PATIENT) with a temporary password, so the patient can actually sign
     *  in to the patient portal afterwards — email is required for exactly that
     *  reason (matching how DoctorController works), so account creation never
     *  silently gets skipped just because someone left an optional field blank.
     *  The generated username/temp password are returned once in this response
     *  (see Patient.tempUsername/tempPassword) for staff to hand over. */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@Valid @RequestBody Patient patient, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can register a patient."));
        }
        if (patient.getEmail() == null || patient.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Email is required to create the patient's portal login."));
        }
        if (userRepository.existsByEmail(patient.getEmail())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "That email is already registered to another account."));
        }

        String username = credentialGenerator.generateUsername(patient.getName());
        String tempPassword = credentialGenerator.generateTempPassword(patient.getName());

        User user = new User(username, passwordEncoder.encode(tempPassword), patient.getEmail(), patient.getName(), Role.PATIENT);
        userRepository.save(user);
        patient.setUser(user);

        Patient saved = patientRepository.save(patient);

        notificationService.notifyUser(saved.getUser(), "Welcome to LifeTag HMS",
                "Your patient portal account has been created. Sign in with username \"" + username
                        + "\" and the temporary password shared with you, then set your own password from Forgot Password.",
                "SYSTEM", null);
        saved.setTempUsername(username);
        saved.setTempPassword(tempPassword);

        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody Patient updated,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can update patient records."));
        }
        return patientRepository.findById(id).<ResponseEntity<?>>map(existing -> {
            // The email is also the linked login account's email, which must stay unique
            // and in sync — reject a clash before touching anything.
            String newEmail = updated.getEmail() == null ? "" : updated.getEmail().trim();
            if (existing.getUser() != null && !newEmail.isEmpty()
                    && !newEmail.equalsIgnoreCase(existing.getUser().getEmail())
                    && userRepository.existsByEmail(newEmail)) {
                return ResponseEntity.badRequest().body(new ApiResponse(false, "That email is already registered to another account."));
            }

            existing.setName(updated.getName());
            existing.setAge(updated.getAge());
            existing.setGender(updated.getGender());
            existing.setContact(updated.getContact());
            existing.setEmail(updated.getEmail());
            existing.setAddress(updated.getAddress());
            existing.setBloodGroup(updated.getBloodGroup());
            existing.setEmergencyContact(updated.getEmergencyContact());
            existing.setStatus(updated.getStatus());
            // Medical-profile fields are only overwritten when the form actually sent them.
            if (updated.getAllergies() != null) existing.setAllergies(updated.getAllergies());
            if (updated.getChronicConditions() != null) existing.setChronicConditions(updated.getChronicConditions());

            if (existing.getUser() != null) {
                existing.getUser().setFullName(existing.getName());
                if (!newEmail.isEmpty()) existing.getUser().setEmail(newEmail);
                userRepository.save(existing.getUser());
            }

            return ResponseEntity.ok(patientRepository.save(existing));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Removes the patient record and disables (rather than deletes) their login, so
     *  past appointments/billing/notifications tied to that account stay intact. */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can remove patient records."));
        }
        Patient patient = patientRepository.findById(id).orElse(null);
        if (patient == null) {
            return ResponseEntity.notFound().build();
        }
        if (!admissionRepository.findByPatientIdAndStatus(id, "Admitted").isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "This patient is currently admitted - discharge them (and settle the bill) before removing the record."));
        }
        boolean owes = billingRepository.findByPatientId(id).stream().anyMatch(b -> !"Cancelled".equalsIgnoreCase(b.getStatus())
                && b.getAmount() != null
                && b.getAmount().subtract(b.getInsuranceCovered() == null ? java.math.BigDecimal.ZERO : b.getInsuranceCovered())
                        .subtract(b.getAmountPaid() == null ? java.math.BigDecimal.ZERO : b.getAmountPaid()).signum() > 0);
        if (owes) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "This patient still has unpaid bills - settle or cancel them before removing the record."));
        }
        if (patient.getUser() != null) {
            patient.getUser().setEnabled(false);
            userRepository.save(patient.getUser());
        }
        patientRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** For a patient who's genuinely forgotten their password (so can't use the
     *  username+old-password self-service change) — Admin/Reception issues a fresh
     *  temporary password, returned once for them to hand over. */
    @PostMapping("/{id}/reset-password")
    @Transactional
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can reset a patient's password."));
        }
        Patient patient = patientRepository.findById(id).orElse(null);
        if (patient == null || patient.getUser() == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "This patient doesn't have a portal login yet."));
        }

        String tempPassword = credentialGenerator.generateTempPassword(patient.getName());
        User user = patient.getUser();
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setEnabled(true);
        userRepository.save(user);

        notificationService.notifyUser(user, "Your password was reset",
                "Hospital staff reset your password. Sign in with the new temporary password shared with you, "
                        + "then change it from the login screen's password change form.",
                "SYSTEM", null);

        patient.setTempUsername(user.getUsername());
        patient.setTempPassword(tempPassword);
        return ResponseEntity.ok(patient);
    }
}
