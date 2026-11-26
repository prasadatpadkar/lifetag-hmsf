package com.lifetag.hms.service;

import com.lifetag.hms.entity.Insurance;
import com.lifetag.hms.entity.InsuranceClaim;
import com.lifetag.hms.entity.Patient;
import com.lifetag.hms.entity.SchemeClaim;
import com.lifetag.hms.util.SimplePdf;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Builds the downloadable PDFs for insurance claims and government-scheme claims. */
@Service
public class ClaimPdfService {

    private static final String HOSPITAL = "LifeTag Hospital";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private static String money(BigDecimal v) {
        return v == null ? "-" : "Rs. " + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String date(LocalDate d) {
        return d == null ? "-" : d.format(DATE);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static void patientBlock(SimplePdf pdf, Patient p) {
        pdf.heading("Patient");
        pdf.row("Name", p == null ? null : p.getName());
        pdf.row("Patient ID", p == null || p.getId() == null ? null : "P-" + p.getId());
        pdf.row("Contact", p == null ? null : p.getContact());
    }

    public byte[] insuranceClaimSummary(InsuranceClaim c) {
        SimplePdf pdf = new SimplePdf().footer(HOSPITAL + " - generated " + date(LocalDate.now()));
        pdf.title("Insurance Claim Summary").subtitle(HOSPITAL).rule();

        pdf.heading("Claim");
        pdf.row("Claim reference", "IC-" + c.getId());
        pdf.row("Invoice", c.getInvoiceRef());
        pdf.row("Status", c.getStatus());
        pdf.row("Submitted on", date(c.getSubmittedDate()));
        pdf.row("Decision date", date(c.getResponseDate()));

        patientBlock(pdf, c.getPatient());

        Insurance ins = c.getInsurance();
        pdf.heading("Policy");
        pdf.row("Provider", ins == null ? null : ins.getProvider());
        pdf.row("Policy number", ins == null ? null : ins.getPolicyNumber());
        pdf.row("Coverage type", ins == null ? null : ins.getCoverageType());
        pdf.row("Coverage amount", ins == null ? null : money(ins.getCoverageAmount()));
        pdf.row("Valid", ins == null ? null : date(ins.getValidFrom()) + " to " + date(ins.getValidTo()));

        pdf.heading("Settlement");
        pdf.row("Claimed amount", money(c.getClaimAmount()));
        pdf.row("Approved amount", "Approved".equals(c.getStatus()) ? money(c.getApprovedAmount()) : "-");
        if (c.getClaimAmount() != null && c.getApprovedAmount() != null && "Approved".equals(c.getStatus())) {
            pdf.row("Balance payable by patient", money(c.getClaimAmount().subtract(c.getApprovedAmount()).max(BigDecimal.ZERO)));
        }
        pdf.row("Remarks", nz(c.getRemarks()));

        pdf.space(10).rule();
        pdf.note("This is a system-generated summary of the claim as recorded by " + HOSPITAL
                + ". The final settlement is decided by the insurance provider; please contact the hospital "
                + "billing desk for any discrepancy.");
        return pdf.toBytes();
    }

    /** Only meaningful for an Approved claim — the controller enforces that. */
    public byte[] schemeSanctionLetter(SchemeClaim c) {
        SimplePdf pdf = new SimplePdf().footer(HOSPITAL + " - generated " + date(LocalDate.now()));
        pdf.title("Scheme Claim Sanction Letter").subtitle(HOSPITAL).rule();

        boolean simulated = "SIMULATED".equalsIgnoreCase(c.getSubmissionMode())
                || (c.getGovtReferenceNumber() != null && c.getGovtReferenceNumber().toUpperCase().startsWith("SANDBOX"));
        if (simulated) {
            pdf.boldParagraph("NOTICE: This claim was recorded through the hospital's simulated (sandbox) submission "
                    + "workflow. This letter is an internal record only and is NOT an official sanction order "
                    + "from any government authority.");
            pdf.rule();
        }

        pdf.heading("Claim");
        pdf.row("Claim reference", "SC-" + c.getId());
        pdf.row("Government reference", c.getGovtReferenceNumber());
        pdf.row("Scheme", c.getScheme() == null ? null : c.getScheme().getName());
        pdf.row("Invoice", c.getInvoiceRef());
        pdf.row("Package", (c.getPackageCode() == null ? "" : c.getPackageCode())
                + (c.getPackageName() == null ? "" : " - " + c.getPackageName()));
        pdf.row("Submitted on", date(c.getSubmittedDate()));
        pdf.row("Decision date", date(c.getResponseDate()));
        pdf.row("Status", c.getStatus());

        patientBlock(pdf, c.getPatient());
        if (c.getEnrollment() != null) {
            pdf.row("Beneficiary card ID", c.getEnrollment().getBeneficiaryCardId());
        }

        pdf.heading("Sanction");
        pdf.row("Claimed amount", money(c.getClaimedAmount()));
        pdf.row("Sanctioned amount", money(c.getSanctionedAmount()));
        pdf.row("Remarks", nz(c.getRemarks()));

        pdf.space(10).rule();
        pdf.note("Aadhaar and other identity numbers are intentionally not printed on this letter.");
        return pdf.toBytes();
    }
}
