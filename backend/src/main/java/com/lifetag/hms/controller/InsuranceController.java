package com.lifetag.hms.controller;

import com.lifetag.hms.util.Input;
import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.Insurance;
import com.lifetag.hms.entity.Patient;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.InsuranceRepository;
import com.lifetag.hms.repository.PatientRepository;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.CoverageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/insurance")
public class InsuranceController {

    @Autowired
    private InsuranceRepository insuranceRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private CoverageService coverageService;
    @Autowired
    private com.lifetag.hms.repository.InsuranceClaimRepository insuranceClaimRepository;

    /** Adds the live "used / remaining sum insured" figures to policies being returned. */
    private <T extends Iterable<Insurance>> T withBalance(T policies) {
        for (Insurance i : policies) withBalance(i);
        return policies;
    }

    private Insurance withBalance(Insurance i) {
        if (i == null || i.getId() == null) return i;
        BigDecimal remaining = coverageService.policyRemaining(i, null);
        BigDecimal total = i.getCoverageAmount() == null ? BigDecimal.ZERO : i.getCoverageAmount();
        i.setRemainingAmount(remaining);
        i.setUtilisedAmount(total.subtract(remaining).max(BigDecimal.ZERO));
        return i;
    }

    private boolean isStaff(UserPrincipal principal) {
        return principal != null
                && (principal.getUser().getRole() == Role.ADMIN || principal.getUser().getRole() == Role.RECEPTION);
    }

    /** Admin/Reception see every policy. A Patient only ever sees their own. */
    @GetMapping
    public List<Insurance> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return List.of();
        if (isStaff(principal)) return withBalance(insuranceRepository.findAll());
        if (principal.getUser().getRole() == Role.PATIENT) {
            return patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> withBalance(insuranceRepository.findByPatientId(p.getId())))
                    .orElse(List.of());
        }
        return List.of();
    }

    /** Staff, or the patient the policy belongs to. */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        Insurance policy = insuranceRepository.findById(id).orElse(null);
        if (policy == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isStaff(principal)) {
            boolean own = principal != null && principal.getUser().getRole() == Role.PATIENT
                    && policy.getPatient() != null
                    && patientRepository.findByUserId(principal.getUser().getId())
                            .map(p -> p.getId().equals(policy.getPatient().getId())).orElse(false);
            if (!own) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own insurance details."));
            }
        }
        return ResponseEntity.ok(withBalance(policy));
    }

    /** A Patient may only request their own patientId; Admin/Reception can request any. */
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<?> byPatient(@PathVariable Long patientId, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal != null && principal.getUser().getRole() == Role.PATIENT) {
            boolean isOwnRecord = patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> p.getId().equals(patientId)).orElse(false);
            if (!isOwnRecord) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own insurance details."));
            }
        }
        return ResponseEntity.ok(withBalance(insuranceRepository.findByPatientId(patientId)));
    }

    /** Only Admin/Reception file a patient's insurance policy details. */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can add insurance details."));
        }
        Patient patient = patientRepository.findById(Input.id(body, "patientId")).orElse(null);
        if (patient == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));
        }
        Insurance insurance = new Insurance();
        insurance.setPatient(patient);
        if (body.get("provider") != null) insurance.setProvider(body.get("provider").toString());
        if (body.get("policyNumber") != null) insurance.setPolicyNumber(body.get("policyNumber").toString());
        if (body.get("coverageAmount") != null) insurance.setCoverageAmount(new BigDecimal(body.get("coverageAmount").toString()));
        if (body.get("coverageType") != null) insurance.setCoverageType(body.get("coverageType").toString());
        if (body.get("validFrom") != null) insurance.setValidFrom(LocalDate.parse(body.get("validFrom").toString()));
        if (body.get("validTo") != null) insurance.setValidTo(LocalDate.parse(body.get("validTo").toString()));
        if (body.get("status") != null) insurance.setStatus(body.get("status").toString());
        checkPolicy(insurance);
        return ResponseEntity.ok(withBalance(insuranceRepository.save(insurance)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can update insurance details."));
        }
        Insurance existing = insuranceRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.get("provider") != null) existing.setProvider(body.get("provider").toString());
        if (body.get("policyNumber") != null) existing.setPolicyNumber(body.get("policyNumber").toString());
        if (body.get("coverageAmount") != null) existing.setCoverageAmount(new BigDecimal(body.get("coverageAmount").toString()));
        if (body.get("coverageType") != null) existing.setCoverageType(body.get("coverageType").toString());
        if (body.get("validFrom") != null) existing.setValidFrom(LocalDate.parse(body.get("validFrom").toString()));
        if (body.get("validTo") != null) existing.setValidTo(LocalDate.parse(body.get("validTo").toString()));
        if (body.get("status") != null) existing.setStatus(body.get("status").toString());
        checkPolicy(existing);
        return ResponseEntity.ok(withBalance(insuranceRepository.save(existing)));
    }

    private void checkPolicy(Insurance i) {
        if (i.getCoverageAmount() != null && i.getCoverageAmount().signum() <= 0) {
            throw new IllegalArgumentException("The sum insured must be more than zero.");
        }
        if (i.getValidFrom() != null && i.getValidTo() != null && i.getValidTo().isBefore(i.getValidFrom())) {
            throw new IllegalArgumentException("The policy end date cannot be before its start date.");
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can remove insurance details."));
        }
        if (!insuranceRepository.existsById(id)) return ResponseEntity.notFound().build();
        if (!insuranceClaimRepository.findByInsuranceId(id).isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "Claims have been filed on this policy, so it must be kept. Mark it Expired instead."));
        }
        insuranceRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
