package com.lifetag.hms.controller;

import com.lifetag.hms.dto.*;
import com.lifetag.hms.entity.Patient;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.entity.User;
import com.lifetag.hms.repository.PatientRepository;
import com.lifetag.hms.repository.UserRepository;
import com.lifetag.hms.security.JwtUtil;
import com.lifetag.hms.security.LoginAttemptService;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.AuditService;
import com.lifetag.hms.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private LoginAttemptService loginAttempts;

    @Autowired
    private AuditService auditService;

    private static ResponseEntity<?> tooManyAttempts(long minutes) {
        return ResponseEntity.status(429).body(new ApiResponse(false,
                "Too many failed attempts. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + "."));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String addr = http.getRemoteAddr();
        long locked = loginAttempts.minutesLocked("login", request.getUsername(), addr);
        if (locked > 0) return tooManyAttempts(locked);
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        } catch (BadCredentialsException e) {
            loginAttempts.recordFailure("login", request.getUsername(), addr);
            Long knownId = userRepository.findByUsername(request.getUsername()).map(User::getId).orElse(null);
            auditService.event("LOGIN_FAIL", knownId, null);
            return ResponseEntity.status(401).body(new ApiResponse(false, "Invalid username or password"));
        } catch (DisabledException e) {
            // Account exists but was deactivated by an administrator (see StaffController/
            // DoctorController/PatientController delete-and-disable flows).
            return ResponseEntity.status(403).body(new ApiResponse(false, "This account has been deactivated. Contact an administrator."));
        }

        loginAttempts.recordSuccess("login", request.getUsername(), addr);
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException("User not found"));

        auditService.event("LOGIN", user.getId(), user.getUsername());
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole().name(), request.isRemember());
        return ResponseEntity.ok(new LoginResponse(token, user.getUsername(), user.getRole().name(), user.getFullName()));
    }

    /**
     * Public self-registration is for PATIENTS ONLY. The role on the incoming request
     * is intentionally ignored and always forced to PATIENT — Doctor, Reception, and
     * Admin accounts are provisioned by an administrator instead (see StaffController
     * for Reception/Admin, DoctorController for Doctors), who hands the new starter a
     * one-time temporary password. Without this restriction, anyone could POST here
     * with role=ADMIN (or DOCTOR/RECEPTION) and grant themselves elevated access to
     * the whole system, since this endpoint has no authentication requirement.
     */
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Username already taken"));
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Email already registered"));
        }

        User user = new User(request.getUsername(), passwordEncoder.encode(request.getPassword()),
                request.getEmail(), request.getFullName(), Role.PATIENT);
        userRepository.save(user);

        notificationService.notifyUser(user, "Welcome to LifeTag HMS",
                "Your account is set up and ready to go.", "SYSTEM", null);

        Patient patient = new Patient();
        patient.setName(request.getFullName());
        patient.setEmail(request.getEmail());
        patient.setUser(user);
        patientRepository.save(patient);

        notificationService.notifyRole(Role.RECEPTION, "New patient registered",
                request.getFullName() + " just signed up through the patient portal.",
                "PATIENT", "reception-patients.html");
        notificationService.notifyRole(Role.ADMIN, "New patient registered",
                request.getFullName() + " just signed up through the patient portal.",
                "PATIENT", "patient-management.html");

        return ResponseEntity.ok(new ApiResponse(true, "Registration successful"));
    }

    /** Ends the session on the server: every token issued so far for this account stops working. */
    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<?> logout(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal != null) {
            User user = userRepository.findById(principal.getUser().getId()).orElse(null);
            if (user != null) {
                user.setTokensValidAfterMs(System.currentTimeMillis());
                userRepository.save(user);
                auditService.event("LOGOUT", user.getId(), user.getUsername());
            }
        }
        return ResponseEntity.ok(new ApiResponse(true, "Signed out."));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(principal.getUser());
    }

    /**
     * Self-service password change — replaces the old email-token "forgot
     * password" flow. Requires the account's CURRENT (or temporary) password,
     * not just a username/email: that's what makes it safe to expose without a
     * login. Proving you know the current password is real proof of identity;
     * knowing a username alone is not, and letting anyone reset a password with
     * just that would let anyone take over any account. Always returns the same
     * generic error whether the account doesn't exist or the old password was
     * wrong, so this can't be used to discover which usernames are registered.
     */
    @PostMapping("/change-password")
    @Transactional
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        String addr = http.getRemoteAddr();
        long locked = loginAttempts.minutesLocked("pwd", request.getUsername(), addr);
        if (locked > 0) return tooManyAttempts(locked);
        User user = userRepository.findByUsername(request.getUsername())
                .or(() -> userRepository.findByEmail(request.getUsername()))
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            loginAttempts.recordFailure("pwd", request.getUsername(), addr);
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Username or current password is incorrect."));
        }
        loginAttempts.recordSuccess("pwd", request.getUsername(), addr);
        if (!user.isEnabled()) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "This account has been deactivated. Contact an administrator."));
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "New password must be different from the current password."));
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setTokensValidAfterMs(System.currentTimeMillis()); // sign out every existing session
        userRepository.save(user);

        notificationService.notifyUser(user, "Password changed",
                "Your password was just updated. If this wasn't you, contact an administrator immediately.",
                "SYSTEM", null);

        return ResponseEntity.ok(new ApiResponse(true, "Password updated — you can now sign in with your new password."));
    }
}
