package com.lifetag.hms.service;

import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.BillingRepository;
import com.lifetag.hms.repository.InsuranceClaimRepository;
import com.lifetag.hms.repository.SchemeClaimRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out how much of an invoice is paid by an insurer or a government scheme.
 *
 * The rule the hospital asked for: cover is deducted from what the patient owes ONLY once the
 * insurer / scheme has SANCTIONED it (claim status "Approved" with a sanctioned amount). A claim
 * that is merely submitted or under review is shown as "awaiting sanction" but never deducted.
 * Sanctioned amounts are also capped by the fixed limits: the insurance policy's sum insured (what
 * is left after earlier claims), the scheme's fixed ceiling, and the invoice total itself.
 */
@Service
public class CoverageService {

    private static final Pattern INVOICE_REF = Pattern.compile("(?i)^\\s*INV-0*(\\d+)\\s*$");
    public static final Set<String> INSURANCE_PENDING = Set.of("Submitted", "Under Review");
    public static final Set<String> SCHEME_PENDING = Set.of("Claim Submitted", "Under Govt Review");

    private final InsuranceClaimRepository insuranceClaims;
    private final SchemeClaimRepository schemeClaims;
    private final BillingRepository billings;

    public CoverageService(InsuranceClaimRepository insuranceClaims, SchemeClaimRepository schemeClaims,
                           BillingRepository billings) {
        this.insuranceClaims = insuranceClaims;
        this.schemeClaims = schemeClaims;
        this.billings = billings;
    }

    /** Outcome of the calculation for one invoice. */
    public static class Result {
        public BigDecimal sanctioned = BigDecimal.ZERO;   // total actually deducted
        public BigDecimal pending = BigDecimal.ZERO;      // claimed, not yet sanctioned
        public List<Map<String, Object>> lines = new ArrayList<>();
        public String note = "";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static Long parseInvoiceId(String ref) {
        if (ref == null) return null;
        Matcher m = INVOICE_REF.matcher(ref);
        if (!m.matches()) return null;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The invoice a claim's invoiceRef ("INV-0005") points at, or null. */
    public Billing billFor(String invoiceRef) {
        Long id = parseInvoiceId(invoiceRef);
        return id == null ? null : billings.findById(id).orElse(null);
    }

    /** Cover for one invoice, from every claim raised against it. */
    public Result compute(String invoiceRef, BigDecimal billAmount) {
        Result r = new Result();
        BigDecimal remaining = nz(billAmount);
        List<String> notes = new ArrayList<>();
        BigDecimal pendingTotal = BigDecimal.ZERO;

        for (InsuranceClaim c : insuranceClaims.findByInvoiceRefIgnoreCase(invoiceRef)) {
            String source = c.getInsurance() == null ? "Insurance"
                    : (c.getInsurance().getProvider() == null ? "Insurance" : c.getInsurance().getProvider())
                    + (c.getInsurance().getPolicyNumber() == null ? "" : " (" + c.getInsurance().getPolicyNumber() + ")");
            boolean approved = "Approved".equals(c.getStatus());
            BigDecimal sanction = approved ? nz(c.getApprovedAmount()) : BigDecimal.ZERO;
            BigDecimal deducted = sanction.min(remaining).max(BigDecimal.ZERO);
            remaining = remaining.subtract(deducted);
            if (INSURANCE_PENDING.contains(c.getStatus())) pendingTotal = pendingTotal.add(nz(c.getClaimAmount()));
            r.lines.add(line("INSURANCE", c.getId(), source, c.getStatus(), nz(c.getClaimAmount()), sanction, deducted));
            r.sanctioned = r.sanctioned.add(deducted);
            if (deducted.signum() > 0) notes.add("Insurance: " + source + " ₹" + deducted.stripTrailingZeros().toPlainString());
        }
        for (SchemeClaim c : schemeClaims.findByInvoiceRefIgnoreCase(invoiceRef)) {
            String source = c.getScheme() == null ? "Government scheme" : c.getScheme().getName();
            boolean approved = "Approved".equals(c.getStatus());
            BigDecimal sanction = approved ? nz(c.getSanctionedAmount()) : BigDecimal.ZERO;
            BigDecimal deducted = sanction.min(remaining).max(BigDecimal.ZERO);
            remaining = remaining.subtract(deducted);
            if (SCHEME_PENDING.contains(c.getStatus())) pendingTotal = pendingTotal.add(nz(c.getClaimedAmount()));
            r.lines.add(line("SCHEME", c.getId(), source, c.getStatus(), nz(c.getClaimedAmount()), sanction, deducted));
            r.sanctioned = r.sanctioned.add(deducted);
            if (deducted.signum() > 0) notes.add("Scheme: " + source + " ₹" + deducted.stripTrailingZeros().toPlainString());
        }
        r.pending = pendingTotal.min(remaining).max(BigDecimal.ZERO);
        String note = String.join("; ", notes);
        r.note = note.length() > 490 ? note.substring(0, 490) : note;
        return r;
    }

    private static Map<String, Object> line(String kind, Long claimId, String source, String status,
                                            BigDecimal claimed, BigDecimal sanctioned, BigDecimal deducted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("claimId", claimId);
        m.put("source", source);
        m.put("status", status);
        m.put("claimed", claimed);
        m.put("sanctioned", sanctioned);
        m.put("deducted", deducted);
        return m;
    }

    // ------------------------------------------------------------------ limits used when a claim is decided

    /** Sanctioned amounts already on this invoice from claims other than the one being decided. */
    private BigDecimal sanctionedElsewhereOnBill(String invoiceRef, Long ignoreInsuranceId, Long ignoreSchemeId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (InsuranceClaim c : insuranceClaims.findByInvoiceRefIgnoreCase(invoiceRef)) {
            if ("Approved".equals(c.getStatus()) && !Objects.equals(c.getId(), ignoreInsuranceId)) sum = sum.add(nz(c.getApprovedAmount()));
        }
        for (SchemeClaim c : schemeClaims.findByInvoiceRefIgnoreCase(invoiceRef)) {
            if ("Approved".equals(c.getStatus()) && !Objects.equals(c.getId(), ignoreSchemeId)) sum = sum.add(nz(c.getSanctionedAmount()));
        }
        return sum;
    }

    /** Sum insured left on a policy: coverageAmount minus everything already sanctioned on it. */
    public BigDecimal policyRemaining(Insurance policy, Long ignoreClaimId) {
        BigDecimal used = BigDecimal.ZERO;
        for (InsuranceClaim c : insuranceClaims.findByInsuranceId(policy.getId())) {
            if ("Approved".equals(c.getStatus()) && !Objects.equals(c.getId(), ignoreClaimId)) used = used.add(nz(c.getApprovedAmount()));
        }
        return nz(policy.getCoverageAmount()).subtract(used).max(BigDecimal.ZERO);
    }

    /** Most an insurer can sanction on this claim: policy balance AND what is still owed on the invoice. */
    public BigDecimal insuranceHeadroom(InsuranceClaim claim) {
        BigDecimal cap = claim.getInsurance() == null ? BigDecimal.ZERO : policyRemaining(claim.getInsurance(), claim.getId());
        Billing bill = billFor(claim.getInvoiceRef());
        if (bill != null) {
            BigDecimal left = nz(bill.getAmount()).subtract(sanctionedElsewhereOnBill(claim.getInvoiceRef(), claim.getId(), null)).max(BigDecimal.ZERO);
            cap = cap.min(left);
        }
        return cap;
    }

    /** Most a scheme can sanction on this claim: the scheme's fixed yearly ceiling and the invoice balance. */
    public BigDecimal schemeHeadroom(SchemeClaim claim) {
        Scheme s = claim.getScheme();
        BigDecimal cap = null;
        if (s != null && s.getMaxCoverAmount() != null) {
            BigDecimal used = BigDecimal.ZERO;
            int year = (claim.getSubmittedDate() != null ? claim.getSubmittedDate() : java.time.LocalDate.now()).getYear();
            for (SchemeClaim c : schemeClaims.findBySchemeIdAndPatientId(s.getId(), claim.getPatient().getId())) {
                if (!"Approved".equals(c.getStatus()) || Objects.equals(c.getId(), claim.getId())) continue;
                int y = (c.getSubmittedDate() != null ? c.getSubmittedDate() : java.time.LocalDate.now()).getYear();
                if (y == year) used = used.add(nz(c.getSanctionedAmount()));
            }
            cap = s.getMaxCoverAmount().subtract(used).max(BigDecimal.ZERO);
        }
        Billing bill = billFor(claim.getInvoiceRef());
        if (bill != null) {
            BigDecimal left = nz(bill.getAmount()).subtract(sanctionedElsewhereOnBill(claim.getInvoiceRef(), null, claim.getId())).max(BigDecimal.ZERO);
            cap = cap == null ? left : cap.min(left);
        }
        return cap == null ? new BigDecimal("99999999.99") : cap;
    }
}
