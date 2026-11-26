package com.lifetag.hms.controller;

import com.lifetag.hms.util.Input;
import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.Patient;
import com.lifetag.hms.entity.PatientScheme;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.entity.Scheme;
import com.lifetag.hms.repository.PatientRepository;
import com.lifetag.hms.repository.PatientSchemeRepository;
import com.lifetag.hms.repository.SchemeRepository;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/patient-schemes")
public class PatientSchemeController {

    @Autowired
    private PatientSchemeRepository patientSchemeRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private SchemeRepository schemeRepository;
    @Autowired
    private NotificationService notificationService;

    private boolean isStaff(UserPrincipal principal) {
        return principal != null
                && (principal.getUser().getRole() == Role.ADMIN || principal.getUser().getRole() == Role.RECEPTION);
    }

    /** Admin/Reception see every enrollment. A Patient only ever sees their own. */
    @GetMapping
    public List<PatientScheme> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return List.of();
        if (isStaff(principal)) return patientSchemeRepository.findAll();
        if (principal.getUser().getRole() == Role.PATIENT) {
            return patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> patientSchemeRepository.findByPatientId(p.getId()))
                    .orElse(List.of());
        }
        return List.of();
    }

    @GetMapping("/patient/{patientId}")
    public ResponseEntity<?> byPatient(@PathVariable Long patientId, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal != null && principal.getUser().getRole() == Role.PATIENT) {
            boolean isOwnRecord = patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> p.getId().equals(patientId)).orElse(false);
            if (!isOwnRecord) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own enrollment."));
            }
        }
        return ResponseEntity.ok(patientSchemeRepository.findByPatientId(patientId));
    }

    /** Only Reception/Admin capture a new enrollment. Body: { "patientId": 1,
     *  "schemeId": 2, "aadhaarNumber": "482913760841", "beneficiaryCardId": "PMJAY-..." } */
    @PostMapping
    @Transactional
    public ResponseEntity<?> enroll(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can enroll a patient in a scheme."));
        }
        Patient patient = patientRepository.findById(Input.id(body, "patientId")).orElse(null);
        Scheme scheme = schemeRepository.findById(Input.id(body, "schemeId")).orElse(null);
        if (patient == null || scheme == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient or scheme id"));
        }
        String aadhaar = body.get("aadhaarNumber") != null ? body.get("aadhaarNumber").toString().replaceAll("\\D", "") : "";
        if (aadhaar.length() != 12) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Aadhaar number must be exactly 12 digits."));
        }

        PatientScheme ps = new PatientScheme();
        ps.setPatient(patient);
        ps.setScheme(scheme);
        ps.setAadhaarNumber(aadhaar);
        ps.setBeneficiaryCardId(body.get("beneficiaryCardId") != null ? body.get("beneficiaryCardId").toString() : null);
        ps.setStatus("Pending Verification");
        return ResponseEntity.ok(patientSchemeRepository.save(ps));
    }

    /** Reception can edit/resubmit enrollment details — this re-opens the
     *  verification gate, since a changed Aadhaar/card ID can't ride on a
     *  previous approval. */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can update an enrollment."));
        }
        PatientScheme existing = patientSchemeRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.get("schemeId") != null) {
            schemeRepository.findById(Input.id(body, "schemeId")).ifPresent(existing::setScheme);
        }
        if (body.get("aadhaarNumber") != null) {
            String aadhaar = body.get("aadhaarNumber").toString().replaceAll("\\D", "");
            if (aadhaar.length() != 12) {
                return ResponseEntity.badRequest().body(new ApiResponse(false, "Aadhaar number must be exactly 12 digits."));
            }
            existing.setAadhaarNumber(aadhaar);
        }
        if (body.get("beneficiaryCardId") != null) existing.setBeneficiaryCardId(body.get("beneficiaryCardId").toString());
        existing.setStatus("Pending Verification");
        existing.setVerifiedBy(null);
        existing.setVerifiedDate(null);
        return ResponseEntity.ok(patientSchemeRepository.save(existing));
    }

    /** Only Admin verifies eligibility. Body: { "status": "Verified" } or { "status": "Rejected" } */
    @PutMapping("/{id}/verify")
    @Transactional
    public ResponseEntity<?> verify(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.getUser().getRole() != Role.ADMIN) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can verify eligibility."));
        }
        PatientScheme existing = patientSchemeRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        String newStatus = body.get("status") != null ? body.get("status").toString() : existing.getStatus();
        existing.setStatus(newStatus);
        if ("Verified".equals(newStatus)) {
            existing.setVerifiedBy(principal.getUser().getFullName());
            existing.setVerifiedDate(LocalDate.now());
        } else {
            existing.setVerifiedBy(null);
            existing.setVerifiedDate(null);
        }
        PatientScheme saved = patientSchemeRepository.save(existing);
        if (saved.getPatient() != null && saved.getPatient().getUser() != null) {
            String schemeName = saved.getScheme() != null ? saved.getScheme().getName() : "the scheme";
            notificationService.notifyUser(saved.getPatient().getUser(), "Scheme eligibility " + newStatus.toLowerCase(),
                    "Your enrollment in " + schemeName + " is now " + newStatus + ".",
                    "SCHEME", "patient-schemes.html");
        }
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can remove an enrollment."));
        }
        if (!patientSchemeRepository.existsById(id)) return ResponseEntity.notFound().build();
        patientSchemeRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
