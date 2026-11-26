package com.lifetag.hms.controller;

import com.lifetag.hms.util.Input;
import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.Insurance;
import com.lifetag.hms.entity.InsuranceClaim;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.InsuranceClaimRepository;
import com.lifetag.hms.repository.InsuranceRepository;
import com.lifetag.hms.repository.PatientRepository;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.entity.Billing;
import com.lifetag.hms.service.BillingSyncService;
import com.lifetag.hms.service.ClaimPdfService;
import com.lifetag.hms.service.CoverageService;
import com.lifetag.hms.service.InvoiceService;
import com.lifetag.hms.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Same separation of duties as Billing: Reception files and tracks a claim,
 * final approve/reject authority stays with Admin, and a Patient can see
 * their own claims but never self-approves one.
 */
@RestController
@RequestMapping("/api/insurance-claims")
public class InsuranceClaimController {

    @Autowired
    private InsuranceClaimRepository insuranceClaimRepository;
    @Autowired
    private InsuranceRepository insuranceRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private ClaimPdfService claimPdfService;
    @Autowired
    private CoverageService coverageService;
    @Autowired
    private BillingSyncService billingSync;

    private boolean isStaff(UserPrincipal principal) {
        return principal != null
                && (principal.getUser().getRole() == Role.ADMIN || principal.getUser().getRole() == Role.RECEPTION);
    }

    @GetMapping
    public List<InsuranceClaim> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return List.of();
        if (isStaff(principal)) return insuranceClaimRepository.findAll();
        if (principal.getUser().getRole() == Role.PATIENT) {
            return patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> insuranceClaimRepository.findByPatientId(p.getId()))
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
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own claims."));
            }
        }
        return ResponseEntity.ok(insuranceClaimRepository.findByPatientId(patientId));
    }

    /** PDF summary of one claim. Staff can download any; a patient only their own. */
    @GetMapping("/{id}/summary.pdf")
    public ResponseEntity<?> summaryPdf(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        InsuranceClaim claim = insuranceClaimRepository.findById(id).orElse(null);
        if (claim == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isStaff(principal)) {
            boolean own = principal != null && principal.getUser().getRole() == Role.PATIENT
                    && claim.getPatient() != null
                    && patientRepository.findByUserId(principal.getUser().getId())
                            .map(p -> p.getId().equals(claim.getPatient().getId())).orElse(false);
            if (!own) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only download your own claim summary."));
            }
        }
        byte[] pdf = claimPdfService.insuranceClaimSummary(claim);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("insurance-claim-" + id + "-summary.pdf").build().toString())
                .body(pdf);
    }

    /** Body: { "policyId": 3, "invoiceRef": "INV-0005", "claimAmount": 2000 } */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can file an insurance claim."));
        }
        Insurance policy = insuranceRepository.findById(Input.id(body, "policyId")).orElse(null);
        if (policy == null || policy.getPatient() == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Add patient insurance details first."));
        }

        String ref = body.get("invoiceRef") == null ? "" : body.get("invoiceRef").toString().trim().toUpperCase();
        Billing bill = coverageService.billFor(ref);
        if (bill == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Enter an existing invoice number such as INV-0005."));
        }
        if (!bill.getPatient().getId().equals(policy.getPatient().getId())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "That invoice belongs to a different patient than the policy holder."));
        }
        if ("Cancelled".equalsIgnoreCase(bill.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "That invoice is cancelled."));
        }
        // The policy must have been in force on the day of treatment (the invoice date), not necessarily today.
        LocalDate treated = bill.getBillDate() != null ? bill.getBillDate() : LocalDate.now();
        if ("Expired".equalsIgnoreCase(policy.getStatus()) && (policy.getValidTo() == null || policy.getValidTo().isBefore(treated))
                || (policy.getValidFrom() != null && policy.getValidFrom().isAfter(treated))
                || (policy.getValidTo() != null && policy.getValidTo().isBefore(treated))) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "This policy (valid " + policy.getValidFrom() + " to " + policy.getValidTo()
                            + ") was not in force on the invoice date " + treated + "."));
        }
        BigDecimal amount = InvoiceService.money(body.get("claimAmount"));
        if (amount.signum() <= 0) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Enter the claim amount (more than zero)."));
        }
        if (amount.compareTo(bill.getAmount()) > 0) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "The claim (₹" + amount + ") is more than the invoice total (₹" + bill.getAmount() + ")."));
        }
        BigDecimal remaining = coverageService.policyRemaining(policy, null);
        if (amount.compareTo(remaining) > 0) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "Only ₹" + remaining + " is left on this policy's sum insured of ₹" + policy.getCoverageAmount() + "."));
        }
        for (InsuranceClaim c : insuranceClaimRepository.findByInvoiceRefIgnoreCase(ref)) {
            if (c.getInsurance() != null && c.getInsurance().getId().equals(policy.getId()) && !"Rejected".equals(c.getStatus())) {
                return ResponseEntity.badRequest().body(new ApiResponse(false,
                        "A claim on this policy is already open for " + ref + " (status " + c.getStatus() + ")."));
            }
        }

        InsuranceClaim claim = new InsuranceClaim();
        claim.setPatient(policy.getPatient());
        claim.setInsurance(policy);
        claim.setInvoiceRef(ref);
        claim.setClaimAmount(amount);
        claim.setApprovedAmount(BigDecimal.ZERO);
        claim.setStatus("Submitted");
        claim.setSubmittedDate(LocalDate.now());

        InsuranceClaim saved = insuranceClaimRepository.save(claim);
        billingSync.syncInvoiceRef(ref, false);          // shows "awaiting sanction" on the invoice, deducts nothing
        return ResponseEntity.ok(saved);
    }

    /** Only Admin decides a claim. Body: { "approvedAmount": 1600, "status": "Approved", "remarks": "..." } */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> decide(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.getUser().getRole() != Role.ADMIN) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can decide an insurance claim."));
        }
        InsuranceClaim existing = insuranceClaimRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.get("status") != null && !InsuranceClaim.STATUSES.contains(body.get("status").toString())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "Invalid claim status. Allowed: " + String.join(", ", InsuranceClaim.STATUSES.stream().sorted().toList()) + "."));
        }
        String target = body.get("status") != null ? body.get("status").toString() : existing.getStatus();
        if ("Approved".equals(target)) {
            BigDecimal approved = body.get("approvedAmount") != null ? InvoiceService.money(body.get("approvedAmount"))
                    : (existing.getApprovedAmount() != null && existing.getApprovedAmount().signum() > 0 ? existing.getApprovedAmount() : BigDecimal.ZERO);
            if (approved.signum() <= 0) {
                return ResponseEntity.badRequest().body(new ApiResponse(false, "Enter the sanctioned amount (more than zero) to approve a claim."));
            }
            if (existing.getClaimAmount() != null && approved.compareTo(existing.getClaimAmount()) > 0) {
                return ResponseEntity.badRequest().body(new ApiResponse(false,
                        "The sanctioned amount cannot be more than the claimed ₹" + existing.getClaimAmount() + "."));
            }
            BigDecimal headroom = coverageService.insuranceHeadroom(existing);
            if (approved.compareTo(headroom) > 0) {
                return ResponseEntity.badRequest().body(new ApiResponse(false,
                        "At most ₹" + headroom + " can be sanctioned: that is what is left on the policy's sum insured and on the invoice."));
            }
            existing.setApprovedAmount(approved);
        } else {
            existing.setApprovedAmount(BigDecimal.ZERO);      // nothing is deducted unless the claim is Approved
        }
        existing.setStatus(target);
        if (body.get("remarks") != null) existing.setRemarks(body.get("remarks").toString());
        existing.setResponseDate("Approved".equals(target) || "Rejected".equals(target) ? LocalDate.now() : null);
        InsuranceClaim saved = insuranceClaimRepository.save(existing);
        billingSync.syncInvoiceRef(saved.getInvoiceRef(), true);     // re-prices the invoice: cover is deducted only now

        if (saved.getPatient() != null && saved.getPatient().getUser() != null) {
            notificationService.notifyUser(saved.getPatient().getUser(), "Insurance claim " + saved.getStatus().toLowerCase(),
                    "Your insurance claim for " + saved.getInvoiceRef() + " is now " + saved.getStatus()
                            + ("Approved".equals(saved.getStatus()) ? ": ₹" + saved.getApprovedAmount() + " sanctioned and deducted from your bill." : "."),
                    "INSURANCE", "patient-insurance.html");
        }
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can remove a claim record."));
        }
        InsuranceClaim claim = insuranceClaimRepository.findById(id).orElse(null);
        if (claim == null) return ResponseEntity.notFound().build();
        String ref = claim.getInvoiceRef();
        insuranceClaimRepository.deleteById(id);
        insuranceClaimRepository.flush();
        billingSync.syncInvoiceRef(ref, true);
        return ResponseEntity.noContent().build();
    }
}
