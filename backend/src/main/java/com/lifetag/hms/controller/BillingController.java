package com.lifetag.hms.controller;

import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.*;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.AdmissionBillingService;
import com.lifetag.hms.service.CoverageService;
import com.lifetag.hms.service.InvoiceService;
import com.lifetag.hms.service.NotificationService;
import com.lifetag.hms.util.Input;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/billing")
public class BillingController {

    @Autowired
    private BillingRepository billingRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private AdmissionRepository admissionRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private DiagnosticReportRepository diagnosticReportRepository;
    @Autowired
    private TreatmentRepository treatmentRepository;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private AdmissionBillingService admissionBilling;
    @Autowired
    private BloodRequestRepository bloodRequestRepository;
    @Autowired
    private InsuranceClaimRepository insuranceClaimRepository;
    @Autowired
    private SchemeClaimRepository schemeClaimRepository;

    private boolean isStaff(UserPrincipal principal) {
        return principal != null
                && (principal.getUser().getRole() == Role.ADMIN || principal.getUser().getRole() == Role.RECEPTION);
    }

    private boolean ownsBill(UserPrincipal principal, Billing bill) {
        return principal != null && principal.getUser().getRole() == Role.PATIENT
                && bill.getPatient() != null
                && patientRepository.findByUserId(principal.getUser().getId())
                        .map(p -> p.getId().equals(bill.getPatient().getId())).orElse(false);
    }

    /** A running inpatient invoice keeps accruing bed days, nursing and visits until discharge, so it
     *  is brought up to date whenever it is read. */
    private Billing fresh(Billing b) {
        if (b == null || b.getAdmission() == null || "Cancelled".equalsIgnoreCase(b.getStatus())) return b;
        if (!"Admitted".equalsIgnoreCase(b.getAdmission().getStatus())) return b;
        Billing synced = admissionBilling.sync(b.getAdmission(), false);
        return synced != null && synced.getId().equals(b.getId()) ? synced : b;
    }

    private List<Billing> fresh(List<Billing> list) {
        List<Billing> out = new ArrayList<>(list.size());
        for (Billing b : list) out.add(fresh(b));
        return out;
    }

    /** Admin/Reception see every bill. A Patient only ever sees their own —
     *  same scoping every other list endpoint in this app already has. */
    @GetMapping
    public List<Billing> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return List.of();
        if (isStaff(principal)) return fresh(billingRepository.findAll());
        if (principal.getUser().getRole() == Role.PATIENT) {
            return patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> fresh(billingRepository.findByPatientId(p.getId())))
                    .orElse(List.of());
        }
        return List.of();
    }

    /** The fixed list of spending categories for the invoice form. */
    @GetMapping("/categories")
    public List<String> categories() {
        return InvoiceService.CATEGORIES;
    }

    /** Staff, or the patient the bill was issued to. */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        Billing bill = billingRepository.findById(id).orElse(null);
        if (bill == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isStaff(principal) && !ownsBill(principal, bill)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own bills."));
        }
        return ResponseEntity.ok(fresh(bill));
    }

    /** Everything the printable invoice needs in one call: the bill, normalised line items,
     *  the per-category "where the money went" breakdown, totals and payment history.
     *  Staff, or the patient the bill was issued to. */
    @GetMapping("/{id}/invoice")
    public ResponseEntity<?> invoice(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        Billing bill = billingRepository.findById(id).orElse(null);
        if (bill == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isStaff(principal) && !ownsBill(principal, bill)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own bills."));
        }
        return ResponseEntity.ok(invoiceService.buildInvoice(fresh(bill)));
    }

    /** A Patient may only request their own patientId; Admin/Reception can request any. */
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<?> byPatient(@PathVariable Long patientId, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal != null && principal.getUser().getRole() == Role.PATIENT) {
            boolean isOwnRecord = patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> p.getId().equals(patientId)).orElse(false);
            if (!isOwnRecord) {
                return ResponseEntity.status(403).body(new ApiResponse(false, "You can only view your own bills."));
            }
        }
        return ResponseEntity.ok(fresh(billingRepository.findByPatientId(patientId)));
    }

    /** Charges sitting in the patient's clinical record that have not been invoiced yet
     *  (consultations, bed days, scans / lab tests, procedures, medicines) as ready-made
     *  line items, so the biller does not have to retype them. Staff only. */
    @GetMapping("/suggest/{patientId}")
    public ResponseEntity<?> suggest(@PathVariable Long patientId, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can generate a bill."));
        }
        if (!patientRepository.existsById(patientId)) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));
        }
        List<Appointment> appts = appointmentRepository.findByPatientIdAndBilledFalseAndStatus(patientId, "Completed");
        // Stays that already have a live (auto-updating) invoice are not suggested again.
        List<Admission> admissions = admissionRepository.findByPatientIdAndBilledFalse(patientId).stream()
                .filter(a -> admissionBilling.activeBill(a) == null).toList();
        // Issued blood that is not on any invoice yet (it is billed automatically while an inpatient invoice is open)
        List<BloodRequest> blood = bloodRequestRepository.findByPatientIdAndStatusAndBilledFalse(patientId, "Fulfilled");
        List<DiagnosticReport> reports = diagnosticReportRepository.findByPatientIdAndBilledFalse(patientId);
        List<Treatment> treatments = treatmentRepository.findByPatientIdAndBilledFalse(patientId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", invoiceService.suggestCharges(appts, admissions, reports, treatments, blood));
        if (!admissions.isEmpty()) {
            Admission latest = admissions.get(admissions.size() - 1);
            out.put("admissionId", latest.getId());
            if (latest.getDoctor() != null) out.put("doctorId", latest.getDoctor().getId());
        } else if (!appts.isEmpty() && appts.get(0).getDoctor() != null) {
            out.put("doctorId", appts.get(0).getDoctor().getId());
        }
        return ResponseEntity.ok(out);
    }

    /** Only Admin/Reception generate a bill — a patient can't invoice themselves. */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can generate a bill."));
        }
        if (body.get("patientId") == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Patient is required."));
        }
        Patient patient = patientRepository.findById(Input.id(body, "patientId")).orElse(null);
        if (patient == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));
        }
        Billing billing = new Billing();
        billing.setPatient(patient);
        applyFields(billing, body);
        invoiceService.recalculate(billing, true);
        if (billing.getRefundDue() != null && billing.getRefundDue().signum() > 0) {
            throw new IllegalArgumentException("Amount paid cannot be more than the invoice total (" + billing.getAmount() + ").");
        }
        if (billing.getAmountPaid() != null && billing.getAmountPaid().signum() > 0
                && (billing.getPaymentsJson() == null || billing.getPaymentsJson().isBlank())) {
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("date", LocalDate.now().toString());
            first.put("amount", billing.getAmountPaid());
            first.put("method", billing.getPaymentMethod() == null ? "Cash" : billing.getPaymentMethod());
            first.put("ref", "");
            billing.setPaymentsJson(invoiceService.toJson(new ArrayList<>(List.of(first))));
        }
        Billing saved = billingRepository.save(billing);
        markSourcesBilled(body);
        storeSourceRefs(saved, body);
        if (saved.getAdmission() != null) {
            Billing synced = admissionBilling.sync(saved.getAdmission(), false);   // adds the stay's auto lines
            if (synced != null) saved = synced;
        }

        notificationService.notifyUser(patient.getUser(), "New bill added",
                "A new bill of ₹" + saved.getAmount() + (saved.getDescription() != null ? " for " + saved.getDescription() : "") + " has been added to your account.",
                "BILLING", "patient-billing.html");

        return ResponseEntity.ok(saved);
    }

    /** Only Admin/Reception update a bill — a patient can't mark their own bill
     *  Paid or change what's owed. */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can update a bill."));
        }
        Billing existing = billingRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if ("Cancelled".equalsIgnoreCase(existing.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "A cancelled invoice cannot be edited. Raise a new invoice instead."));
        }
        boolean cancelling = body.get("status") != null && "Cancelled".equalsIgnoreCase(body.get("status").toString());
        if (cancelling) {
            if (existing.getAmountPaid() != null && existing.getAmountPaid().signum() > 0) {
                return ResponseEntity.badRequest().body(new ApiResponse(false,
                        "Payments of ₹" + existing.getAmountPaid() + " were received on this invoice - refund them before cancelling."));
            }
            if (!claimsOn(existing).isEmpty()) {
                return ResponseEntity.badRequest().body(new ApiResponse(false,
                        "Insurance / scheme claims are filed against this invoice - withdraw them before cancelling."));
            }
        }
        applyFields(existing, body);
        invoiceService.recalculate(existing, true);
        Billing saved = billingRepository.save(existing);
        if (cancelling) {
            admissionBilling.release(saved);          // give the tests / blood / bed days back so they can be billed again
        } else {
            markSourcesBilled(body);
            storeSourceRefs(saved, body);
            if (saved.getAdmission() != null) {
                Billing synced = admissionBilling.sync(saved.getAdmission(), false);   // auto lines cannot be edited away
                if (synced != null && synced.getId().equals(saved.getId())) saved = synced;
            }
        }
        return ResponseEntity.ok(saved);
    }

    /** Records a payment received against a bill (partial or full) and appends it to the
     *  bill's payment history. Body: { "amount": 500, "method": "UPI", "reference": "..." } */
    @PostMapping("/{id}/payments")
    @Transactional
    public ResponseEntity<?> addPayment(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can record a payment."));
        }
        Billing bill = billingRepository.findById(id).orElse(null);
        if (bill == null) {
            return ResponseEntity.notFound().build();
        }
        if ("Cancelled".equalsIgnoreCase(bill.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "This invoice is cancelled - payments cannot be recorded."));
        }
        BigDecimal amount = InvoiceService.money(body.get("amount"));
        if (amount.signum() <= 0) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Enter a payment amount greater than zero."));
        }
        BigDecimal payable = (bill.getAmount() == null ? BigDecimal.ZERO : bill.getAmount())
                .subtract(bill.getInsuranceCovered() == null ? BigDecimal.ZERO : bill.getInsuranceCovered()).max(BigDecimal.ZERO);
        BigDecimal paid = bill.getAmountPaid() == null ? BigDecimal.ZERO : bill.getAmountPaid();
        if (paid.add(amount).compareTo(payable) > 0) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "That is more than the outstanding balance of ₹" + payable.subtract(paid) + "."));
        }
        String method = body.get("method") == null ? "Cash" : body.get("method").toString();
        List<Map<String, Object>> payments = invoiceService.parseList(bill.getPaymentsJson());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("date", LocalDate.now().toString());
        p.put("amount", amount);
        p.put("method", method);
        p.put("ref", body.get("reference") == null ? "" : body.get("reference").toString());
        payments.add(p);
        bill.setPaymentsJson(invoiceService.toJson(payments));
        bill.setAmountPaid(paid.add(amount));
        bill.setPaymentMethod(method);
        invoiceService.recalculate(bill, false);
        Billing saved = billingRepository.save(bill);

        notificationService.notifyUser(bill.getPatient().getUser(), "Payment received",
                "We received ₹" + amount + " (" + method + ") towards invoice " + InvoiceService.invoiceNo(saved) + ".",
                "BILLING", "patient-billing.html");
        return ResponseEntity.ok(saved);
    }

    /** Admin or Reception can remove a bill record — matches the existing
     *  shared billing UI, where both roles already have this delete button. */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only Admin or Reception can remove a bill record."));
        }
        Billing bill = billingRepository.findById(id).orElse(null);
        if (bill == null) return ResponseEntity.notFound().build();
        if (bill.getAmountPaid() != null && bill.getAmountPaid().signum() > 0) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "Payments were received on this invoice, so it must be kept for the accounts. Cancel it after refunding instead."));
        }
        if (!claimsOn(bill).isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "Insurance / scheme claims are filed against this invoice - remove them first."));
        }
        if (!"Cancelled".equalsIgnoreCase(bill.getStatus())) admissionBilling.release(bill);
        billingRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ helpers

    private void applyFields(Billing b, Map<String, Object> body) {
        if (body.get("description") != null) b.setDescription(body.get("description").toString());
        if (body.get("amount") != null) b.setAmount(InvoiceService.money(body.get("amount")));
        if (body.get("discount") != null) b.setDiscount(InvoiceService.money(body.get("discount")));
        if (body.get("amountPaid") != null) b.setAmountPaid(InvoiceService.money(body.get("amountPaid")));
        // insuranceCovered / insuranceNote are deliberately NOT accepted from the client: cover is derived
        // from sanctioned claims (see CoverageService) so it cannot be typed in or inflated.
        if (body.get("status") != null) b.setStatus(body.get("status").toString());
        if (body.get("paymentMethod") != null) b.setPaymentMethod(body.get("paymentMethod").toString());
        if (body.get("itemsJson") != null) b.setItemsJson(body.get("itemsJson").toString());
        if (body.get("notes") != null) b.setNotes(body.get("notes").toString());
        if (body.get("billDate") != null) b.setBillDate(LocalDate.parse(body.get("billDate").toString()));
        if (body.get("dueDate") != null && !body.get("dueDate").toString().isBlank()) {
            b.setDueDate(LocalDate.parse(body.get("dueDate").toString()));
        }
        if (body.containsKey("doctorId")) {
            b.setDoctor(body.get("doctorId") == null || body.get("doctorId").toString().isBlank()
                    ? null : doctorRepository.findById(Input.id(body, "doctorId")).orElse(null));
        }
        if (body.containsKey("admissionId")) {
            b.setAdmission(body.get("admissionId") == null || body.get("admissionId").toString().isBlank()
                    ? null : admissionRepository.findById(Input.id(body, "admissionId")).orElse(null));
        }
    }

    private List<Object> claimsOn(Billing bill) {
        String ref = InvoiceService.invoiceNo(bill);
        List<Object> all = new ArrayList<>(insuranceClaimRepository.findByInvoiceRefIgnoreCase(ref));
        all.addAll(schemeClaimRepository.findByInvoiceRefIgnoreCase(ref));
        return all;
    }

    /** Remember which clinical records fed this invoice so cancelling / deleting it can release them. */
    @SuppressWarnings("unchecked")
    private void storeSourceRefs(Billing bill, Map<String, Object> body) {
        Object refs = body.get("sourceRefs");
        if (!(refs instanceof List)) return;
        List<Map<String, Object>> merged = invoiceService.parseList(bill.getSourceRefsJson());
        for (Object o : (List<Object>) refs) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> r = (Map<String, Object>) o;
            if (r.get("type") == null || r.get("id") == null) continue;
            boolean dup = merged.stream().anyMatch(m -> String.valueOf(m.get("type")).equals(String.valueOf(r.get("type")))
                    && String.valueOf(m.get("id")).equals(String.valueOf(r.get("id"))));
            if (!dup) merged.add(new LinkedHashMap<>(r));
        }
        bill.setSourceRefsJson(invoiceService.toJson(merged));
        billingRepository.save(bill);
    }

    /** body.sourceRefs = [{type:"APPOINTMENT"|"ADMISSION"|"REPORT"|"TREATMENT"|"TREATMENT_MEDS"|"BLOOD", id:n}, ...]
     *  Marks the clinical records that were turned into line items as billed, so they are
     *  not suggested (and charged) a second time. */
    @SuppressWarnings("unchecked")
    private void markSourcesBilled(Map<String, Object> body) {
        Object refs = body.get("sourceRefs");
        if (!(refs instanceof List)) return;
        for (Object o : (List<Object>) refs) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> ref = (Map<String, Object>) o;
            if (ref.get("type") == null || ref.get("id") == null) continue;
            Long refId = Input.id(ref, "id");
            switch (ref.get("type").toString()) {
                case "APPOINTMENT" -> appointmentRepository.findById(refId).ifPresent(a -> {
                    a.setBilled(true);
                    appointmentRepository.save(a);
                });
                case "ADMISSION" -> admissionRepository.findById(refId).ifPresent(a -> {
                    // a running stay is billed up to today only; it is fully billed once discharged
                    a.setBilledUpTo(a.getDischargedOn() != null ? a.getDischargedOn() : LocalDate.now());
                    a.setBilled("Discharged".equalsIgnoreCase(a.getStatus()));
                    admissionRepository.save(a);
                });
                case "REPORT" -> diagnosticReportRepository.findById(refId).ifPresent(r -> {
                    r.setBilled(true);
                    diagnosticReportRepository.save(r);
                });
                case "TREATMENT", "TREATMENT_MEDS" -> treatmentRepository.findById(refId).ifPresent(t -> {
                    t.setBilled(true);
                    treatmentRepository.save(t);
                });
                case "BLOOD" -> bloodRequestRepository.findById(refId).ifPresent(r -> {
                    r.setBilled(true);
                    bloodRequestRepository.save(r);
                });
                default -> { }
            }
        }
    }
}
