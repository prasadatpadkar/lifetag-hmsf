package com.lifetag.hms.controller;

import com.lifetag.hms.util.Input;
import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.*;
import com.lifetag.hms.security.UserPrincipal;
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

@RestController
@RequestMapping("/api/scheme-claims")
public class SchemeClaimController {

    @Autowired
    private SchemeClaimRepository schemeClaimRepository;
    @Autowired
    private PatientSchemeRepository patientSchemeRepository;
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
    public List<SchemeClaim> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return List.of();
        if (isStaff(principal)) return schemeClaimRepository.findAll();
        if (principal.getUser().getRole() == Role.PATIENT) {
            return patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> schemeClaimRepository.findByPatientId(p.getId()))
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
        return ResponseEntity.ok(schemeClaimRepository.findByPatientId(patientId));
    }

    /** PDF sanction letter — only exists once a claim is Approved. Staff can download
     *  any; a patient only their own. */
    @GetMapping("/{id}/sanction-letter.pdf")
    public ResponseEntity<?> sanctionLetterPdf(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        SchemeClaim claim = schemeClaimRepository.findById(id).orElse(null);
        if (claim == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isStaff(principal)) {
            boolean own = principal != null && principal.getUser().getRole() == Role.PATIENT
                    && claim.getPatient() != null
                    && patientRepository.findByUserId(principal.getUser().getId())
                            .map(p -> p.getId().equals(claim.getPatient().getId())).orElse(false);
            if (!own) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only download your own sanction letter."));
            }
        }
        if (!"Approved".equals(claim.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "A sanction letter is only available once the claim is Approved (current status: " + claim.getStatus() + ")."));
        }
        byte[] pdf = claimPdfService.schemeSanctionLetter(claim);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("scheme-claim-" + id + "-sanction-letter.pdf").build().toString())
                .body(pdf);
    }

    /** Only Reception/Admin file a claim, and only against an enrollment already
     *  marked "Verified" — reception can't self-certify eligibility for a
     *  government subsidy. Body: { "enrollmentId": 1, "invoiceRef": "INV-0005",
     *  "packageCode": "HBP-1102", "packageName": "...", "claimedAmount": 1800,
     *  "status": "Claim Submitted", "submissionMode": "SIMULATED",
     *  "govtReferenceNumber": "SANDBOX-1001", "submittedDate": "2026-07-19",
     *  "auditTrailJson": "[...]" }
     *  The government-submission step itself (see schemes-reception.js) stays a
     *  frontend-only simulation — there's no live portal integration in this
     *  build — this endpoint just durably persists whatever that step produced. */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can file a scheme claim."));
        }
        PatientScheme enrollment = patientSchemeRepository.findById(Input.id(body, "enrollmentId")).orElse(null);
        if (enrollment == null || !"Verified".equals(enrollment.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Only verified enrollments can be claimed against."));
        }

        if (body.get("status") != null && !SchemeClaim.STATUSES.contains(body.get("status").toString())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "Invalid claim status. Allowed: " + String.join(", ", SchemeClaim.STATUSES.stream().sorted().toList()) + "."));
        }

        String ref = body.get("invoiceRef") == null ? "" : body.get("invoiceRef").toString().trim().toUpperCase();
        Billing bill = coverageService.billFor(ref);
        if (bill == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Enter an existing invoice number such as INV-0005."));
        }
        if (!bill.getPatient().getId().equals(enrollment.getPatient().getId())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "That invoice belongs to a different patient than the enrolled beneficiary."));
        }
        if ("Cancelled".equalsIgnoreCase(bill.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "That invoice is cancelled."));
        }
        if (enrollment.getScheme() != null && enrollment.getScheme().getStatus() != null
                && !"Active".equalsIgnoreCase(enrollment.getScheme().getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "This scheme is not active - claims cannot be filed."));
        }
        BigDecimal claimed = InvoiceService.money(body.get("claimedAmount"));
        if (claimed.signum() <= 0) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Enter the claimed amount (more than zero)."));
        }
        if (claimed.compareTo(bill.getAmount()) > 0) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "The claim (₹" + claimed + ") is more than the invoice total (₹" + bill.getAmount() + ")."));
        }
        for (SchemeClaim c : schemeClaimRepository.findByInvoiceRefIgnoreCase(ref)) {
            if (c.getEnrollment() != null && c.getEnrollment().getId().equals(enrollment.getId()) && !"Rejected".equals(c.getStatus())) {
                return ResponseEntity.badRequest().body(new ApiResponse(false,
                        "A claim under this scheme is already open for " + ref + " (status " + c.getStatus() + ")."));
            }
        }

        SchemeClaim claim = new SchemeClaim();
        claim.setPatient(enrollment.getPatient());
        claim.setScheme(enrollment.getScheme());
        claim.setEnrollment(enrollment);
        claim.setInvoiceRef(ref);
        if (body.get("packageCode") != null) claim.setPackageCode(body.get("packageCode").toString());
        if (body.get("packageName") != null) claim.setPackageName(body.get("packageName").toString());
        claim.setClaimedAmount(claimed);
        claim.setSanctionedAmount(BigDecimal.ZERO);
        // a claim can only be created as Draft / submitted - sanction is the Admin's decision (PUT)
        String initial = body.get("status") == null ? "Draft" : body.get("status").toString();
        if ("Approved".equals(initial) || "Rejected".equals(initial)) initial = "Claim Submitted";
        claim.setStatus(initial);
        if (body.get("submissionMode") != null) claim.setSubmissionMode(body.get("submissionMode").toString());
        if (body.get("govtReferenceNumber") != null) claim.setGovtReferenceNumber(body.get("govtReferenceNumber").toString());
        claim.setSubmittedDate(body.get("submittedDate") != null ? LocalDate.parse(body.get("submittedDate").toString()) : LocalDate.now());
        if (body.get("auditTrailJson") != null) claim.setAuditTrailJson(body.get("auditTrailJson").toString());

        SchemeClaim saved = schemeClaimRepository.save(claim);
        billingSync.syncInvoiceRef(ref, false);          // shows "awaiting sanction" on the invoice, deducts nothing
        return ResponseEntity.ok(saved);
    }

    /** Only Admin decides a claim. Body: { "sanctionedAmount": 1800, "status": "Approved",
     *  "govtReferenceNumber": "PMJAY-CL-88213", "remarks": "...", "auditTrailJson": "[...]" } */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> decide(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.getUser().getRole() != Role.ADMIN) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can decide a scheme claim."));
        }
        SchemeClaim existing = schemeClaimRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        if (body.get("status") != null && !SchemeClaim.STATUSES.contains(body.get("status").toString())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "Invalid claim status. Allowed: " + String.join(", ", SchemeClaim.STATUSES.stream().sorted().toList()) + "."));
        }

        String target = body.get("status") != null ? body.get("status").toString() : existing.getStatus();
        if ("Approved".equals(target)) {
            BigDecimal sanctioned = body.get("sanctionedAmount") != null ? InvoiceService.money(body.get("sanctionedAmount"))
                    : (existing.getSanctionedAmount() != null && existing.getSanctionedAmount().signum() > 0 ? existing.getSanctionedAmount() : BigDecimal.ZERO);
            if (sanctioned.signum() <= 0) {
                return ResponseEntity.badRequest().body(new ApiResponse(false, "Enter the sanctioned amount (more than zero) to approve a claim."));
            }
            if (existing.getClaimedAmount() != null && sanctioned.compareTo(existing.getClaimedAmount()) > 0) {
                return ResponseEntity.badRequest().body(new ApiResponse(false,
                        "The sanctioned amount cannot be more than the claimed ₹" + existing.getClaimedAmount() + "."));
            }
            BigDecimal headroom = coverageService.schemeHeadroom(existing);
            if (sanctioned.compareTo(headroom) > 0) {
                return ResponseEntity.badRequest().body(new ApiResponse(false,
                        "At most ₹" + headroom + " can be sanctioned: that is what is left of the scheme's fixed limit and of the invoice."));
            }
            existing.setSanctionedAmount(sanctioned);
        } else {
            existing.setSanctionedAmount(BigDecimal.ZERO);    // nothing is deducted unless the claim is Approved
        }
        existing.setStatus(target);
        if (body.get("govtReferenceNumber") != null) existing.setGovtReferenceNumber(body.get("govtReferenceNumber").toString());
        if (body.get("submissionMode") != null) existing.setSubmissionMode(body.get("submissionMode").toString());
        if (body.get("remarks") != null) existing.setRemarks(body.get("remarks").toString());
        if (body.get("auditTrailJson") != null) existing.setAuditTrailJson(body.get("auditTrailJson").toString());
        existing.setResponseDate("Approved".equals(target) || "Rejected".equals(target) ? LocalDate.now() : null);

        SchemeClaim saved = schemeClaimRepository.save(existing);
        billingSync.syncInvoiceRef(saved.getInvoiceRef(), true);     // re-prices the invoice: cover is deducted only now
        if (saved.getPatient() != null && saved.getPatient().getUser() != null) {
            notificationService.notifyUser(saved.getPatient().getUser(), "Scheme claim " + saved.getStatus().toLowerCase(),
                    "Your government scheme claim for " + saved.getInvoiceRef() + " is now " + saved.getStatus()
                            + ("Approved".equals(saved.getStatus()) ? ": ₹" + saved.getSanctionedAmount() + " sanctioned and deducted from your bill." : "."),
                    "SCHEME", "patient-schemes.html");
        }
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can remove a claim record."));
        }
        SchemeClaim claim = schemeClaimRepository.findById(id).orElse(null);
        if (claim == null) return ResponseEntity.notFound().build();
        String ref = claim.getInvoiceRef();
        schemeClaimRepository.deleteById(id);
        schemeClaimRepository.flush();
        billingSync.syncInvoiceRef(ref, true);
        return ResponseEntity.noContent().build();
    }
}
