package com.lifetag.hms.controller;

import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.Doctor;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.entity.User;
import com.lifetag.hms.repository.DoctorRepository;
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
@RequestMapping("/api/doctors")
public class DoctorController {

    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CredentialGenerator credentialGenerator;
    @Autowired
    private NotificationService notificationService;

    private boolean isAdmin(UserPrincipal principal) {
        return principal != null && principal.getUser().getRole() == Role.ADMIN;
    }

    @GetMapping
    public List<Doctor> getAll() {
        return doctorRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Doctor> getById(@PathVariable Long id) {
        return doctorRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Resolves the Doctor profile for the currently signed-in account — this is
     *  how the doctor portal reliably knows "who am I" (their real doctor id,
     *  specialization, etc.) from the JWT session, instead of guessing from
     *  whatever name was typed on the login screen. */
    @GetMapping("/me")
    public ResponseEntity<?> getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.getUser().getRole() != Role.DOCTOR) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only a doctor account can access this."));
        }
        return doctorRepository.findByUserId(principal.getUser().getId())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(new ApiResponse(false, "No doctor profile is linked to this account yet — contact an administrator.")));
    }

    /** Adding a doctor also creates their login (role DOCTOR) with a temporary
     *  password, so they can actually sign in to the doctor portal afterwards.
     *  The generated username/temp password are returned once in this response
     *  (see Doctor.tempUsername/tempPassword) for Admin to hand over. */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@Valid @RequestBody Doctor doctor, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can add doctors."));
        }
        if (doctor.getEmail() == null || doctor.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Email is required to create the doctor's login."));
        }
        if (userRepository.existsByEmail(doctor.getEmail())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "That email is already registered to another account."));
        }

        String username = credentialGenerator.generateUsername(doctor.getName());
        String tempPassword = credentialGenerator.generateTempPassword(doctor.getName());

        User user = new User(username, passwordEncoder.encode(tempPassword), doctor.getEmail(), doctor.getName(), Role.DOCTOR);
        userRepository.save(user);
        doctor.setUser(user);

        if (doctor.getConsultationFee() == null) doctor.setConsultationFee(new java.math.BigDecimal("500"));
        Doctor saved = doctorRepository.save(doctor);

        notificationService.notifyUser(user, "Welcome to LifeTag HMS",
                "Your doctor account has been created. Sign in with username \"" + username
                        + "\" and the temporary password shared with you, then set your own password from Forgot Password.",
                "SYSTEM", null);

        saved.setTempUsername(username);
        saved.setTempPassword(tempPassword);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody Doctor updated,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can update doctor records."));
        }
        return doctorRepository.findById(id).<ResponseEntity<?>>map(existing -> {
            // The email is also the linked login account's email, which must stay unique
            // and in sync — reject a clash before touching anything.
            String newEmail = updated.getEmail() == null ? "" : updated.getEmail().trim();
            if (existing.getUser() != null && !newEmail.isEmpty()
                    && !newEmail.equalsIgnoreCase(existing.getUser().getEmail())
                    && userRepository.existsByEmail(newEmail)) {
                return ResponseEntity.badRequest().body(new ApiResponse(false, "That email is already registered to another account."));
            }

            existing.setName(updated.getName());
            existing.setSpecialization(updated.getSpecialization());
            existing.setDepartment(updated.getDepartment());
            existing.setContact(updated.getContact());
            existing.setEmail(updated.getEmail());
            existing.setQualification(updated.getQualification());
            existing.setExperienceYears(updated.getExperienceYears());
            existing.setAvailableDays(updated.getAvailableDays());
            existing.setStatus(updated.getStatus());
            if (updated.getConsultationFee() != null) existing.setConsultationFee(updated.getConsultationFee());

            // Keep the linked login account's display name and email in sync so they show
            // correctly at login and in notifications; the username itself never changes.
            if (existing.getUser() != null) {
                existing.getUser().setFullName(existing.getName());
                if (!newEmail.isEmpty()) existing.getUser().setEmail(newEmail);
                userRepository.save(existing.getUser());
            }

            return ResponseEntity.ok(doctorRepository.save(existing));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Removes the doctor record and disables (rather than deletes) their login, so
     *  past appointments/feedback/notifications tied to that account stay intact. */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can remove doctor records."));
        }
        Doctor doctor = doctorRepository.findById(id).orElse(null);
        if (doctor == null) {
            return ResponseEntity.notFound().build();
        }
        if (doctor.getUser() != null) {
            doctor.getUser().setEnabled(false);
            userRepository.save(doctor.getUser());
        }
        doctorRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** For a doctor who's genuinely forgotten their password (so can't use the
     *  username+old-password self-service change) — Admin issues a fresh
     *  temporary password, returned once for them to hand over. */
    @PostMapping("/{id}/reset-password")
    @Transactional
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can reset a doctor's password."));
        }
        Doctor doctor = doctorRepository.findById(id).orElse(null);
        if (doctor == null || doctor.getUser() == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "This doctor doesn't have a login account yet."));
        }

        String tempPassword = credentialGenerator.generateTempPassword(doctor.getName());
        User user = doctor.getUser();
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setEnabled(true);
        userRepository.save(user);

        notificationService.notifyUser(user, "Your password was reset",
                "An administrator reset your password. Sign in with the new temporary password shared with you, "
                        + "then change it from the login screen's password change form.",
                "SYSTEM", null);

        doctor.setTempUsername(user.getUsername());
        doctor.setTempPassword(tempPassword);
        return ResponseEntity.ok(doctor);
    }
}
