package com.lifetag.hms.controller;

import com.lifetag.hms.util.Input;
import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.dto.UserSummaryResponse;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.entity.User;
import com.lifetag.hms.repository.UserRepository;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.NotificationService;
import com.lifetag.hms.util.CredentialGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin-only management of Reception-desk and Admin login accounts.
 *
 * There is no separate "Receptionist" domain entity in this system — a receptionist
 * is simply a {@link User} with role RECEPTION, the same way another admin is a
 * User with role ADMIN. Doctors and Patients have their own richer profile
 * entities/controllers (DoctorController, PatientController) so they are not
 * handled here.
 *
 * Every endpoint in this controller requires the caller to already be signed in
 * as ADMIN — Reception and other roles get a 403.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CredentialGenerator credentialGenerator;
    @Autowired private NotificationService notificationService;

    private ResponseEntity<?> requireAdmin(UserPrincipal principal) {
        if (principal == null || principal.getUser().getRole() != Role.ADMIN) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can manage staff accounts."));
        }
        return null;
    }

    /** Only RECEPTION and ADMIN accounts are manageable through this controller. */
    private Role parseManageableRole(String raw) {
        try {
            Role role = Role.valueOf(raw.toUpperCase());
            return (role == Role.RECEPTION || role == Role.ADMIN) ? role : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @GetMapping("/{role}")
    public ResponseEntity<?> list(@PathVariable String role, @AuthenticationPrincipal UserPrincipal principal) {
        ResponseEntity<?> denied = requireAdmin(principal);
        if (denied != null) return denied;

        Role target = parseManageableRole(role);
        if (target == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "role must be RECEPTION or ADMIN"));
        }

        List<UserSummaryResponse> result = userRepository.findByRole(target).stream()
                .map(UserSummaryResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Body: { "fullName": "Neha Kapoor", "email": "neha.kapoor@lifetag.com" } */
    @PostMapping("/{role}")
    @Transactional
    public ResponseEntity<?> create(@PathVariable String role, @RequestBody Map<String, String> body,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        ResponseEntity<?> denied = requireAdmin(principal);
        if (denied != null) return denied;

        Role target = parseManageableRole(role);
        if (target == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "role must be RECEPTION or ADMIN"));
        }

        String fullName = body.getOrDefault("fullName", "").trim();
        String email = body.getOrDefault("email", "").trim();
        if (fullName.isEmpty() || email.isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Full name and email are required."));
        }
        Input.text(fullName, "Full name", 100);
        email = Input.email(email, "Email");
        if (userRepository.existsByEmail(email)) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "That email is already registered to another account."));
        }

        String username = credentialGenerator.generateUsername(fullName);
        String tempPassword = credentialGenerator.generateTempPassword(fullName);

        User user = new User(username, passwordEncoder.encode(tempPassword), email, fullName, target);
        userRepository.save(user);

        notificationService.notifyUser(user, "Welcome to LifeTag HMS",
                "Your " + (target == Role.ADMIN ? "admin" : "reception") + " account has been created. Sign in with username \""
                        + username + "\" and the temporary password shared with you, then set your own password from Forgot Password.",
                "SYSTEM", null);

        UserSummaryResponse response = UserSummaryResponse.from(user);
        response.setTempPassword(tempPassword);
        return ResponseEntity.ok(response);
    }

    /** Body: any of { "fullName": "...", "email": "...", "enabled": true|false } */
    @PutMapping("/account/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        ResponseEntity<?> denied = requireAdmin(principal);
        if (denied != null) return denied;

        User user = userRepository.findById(id).orElse(null);
        if (user == null || (user.getRole() != Role.RECEPTION && user.getRole() != Role.ADMIN)) {
            return ResponseEntity.notFound().build();
        }

        if (body.get("fullName") != null && !body.get("fullName").toString().isBlank()) {
            user.setFullName(Input.text(body.get("fullName").toString(), "Full name", 100));
        }
        if (body.get("email") != null && !body.get("email").toString().isBlank()) {
            String newEmail = Input.email(body.get("email").toString(), "Email");
            if (!newEmail.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmail(newEmail)) {
                return ResponseEntity.badRequest().body(new ApiResponse(false, "That email is already registered to another account."));
            }
            user.setEmail(newEmail);
        }
        if (body.get("enabled") != null) {
            user.setEnabled(Boolean.parseBoolean(body.get("enabled").toString()));
        }

        userRepository.save(user);
        return ResponseEntity.ok(UserSummaryResponse.from(user));
    }

    /** Revokes login access rather than hard-deleting the row, so history (notifications,
     *  staff activity, past bookings they touched) stays intact and no foreign-key data is orphaned. */
    @DeleteMapping("/account/{id}")
    @Transactional
    public ResponseEntity<?> deactivate(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        ResponseEntity<?> denied = requireAdmin(principal);
        if (denied != null) return denied;

        User user = userRepository.findById(id).orElse(null);
        if (user == null || (user.getRole() != Role.RECEPTION && user.getRole() != Role.ADMIN)) {
            return ResponseEntity.notFound().build();
        }
        if (user.getId().equals(principal.getUser().getId())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "You can't remove your own account."));
        }
        if (user.getRole() == Role.ADMIN) {
            long activeAdmins = userRepository.findByRole(Role.ADMIN).stream().filter(User::isEnabled).count();
            if (activeAdmins <= 1) {
                return ResponseEntity.badRequest().body(new ApiResponse(false, "At least one active administrator must remain."));
            }
        }

        user.setEnabled(false);
        userRepository.save(user);
        return ResponseEntity.ok(new ApiResponse(true, "Account access removed."));
    }

    /** For someone who's genuinely forgotten their password (so can't use the
     *  username+old-password self-service change) — Admin issues a fresh temporary
     *  password, returned once for them to hand over. */
    @PostMapping("/account/{id}/reset-password")
    @Transactional
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        ResponseEntity<?> denied = requireAdmin(principal);
        if (denied != null) return denied;

        User user = userRepository.findById(id).orElse(null);
        if (user == null || (user.getRole() != Role.RECEPTION && user.getRole() != Role.ADMIN)) {
            return ResponseEntity.notFound().build();
        }

        String tempPassword = credentialGenerator.generateTempPassword(user.getFullName());
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setEnabled(true);
        userRepository.save(user);

        notificationService.notifyUser(user, "Your password was reset",
                "An administrator reset your password. Sign in with the new temporary password shared with you, "
                        + "then change it from the login screen's password change form.",
                "SYSTEM", null);

        UserSummaryResponse response = UserSummaryResponse.from(user);
        response.setTempPassword(tempPassword);
        return ResponseEntity.ok(response);
    }
}
