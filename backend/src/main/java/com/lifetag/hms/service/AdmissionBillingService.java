package com.lifetag.hms.service;

import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Keeps ONE running invoice per inpatient stay up to date, so nothing that happens to an admitted
 * patient is forgotten at billing time:
 *
 *   - room / bed days at the tariff snapshotted when the bed was allotted (accrues daily until discharge)
 *   - nursing & care and daily doctor visits for those days
 *   - blood issued from the blood bank
 *   - diagnostic reports (lab / radiology / cardiac) done during the stay
 *   - procedures, OT charges and medicines from the stay's treatments
 *
 * These lines carry an "auto" key and are regenerated on every sync; anything the billing desk typed
 * in by hand (no "auto" key) is left alone. Insurance / scheme cover is applied afterwards by
 * {@link InvoiceService#recalculate}, only for amounts that have been sanctioned.
 */
@Service
public class AdmissionBillingService {

    private final BillingRepository billings;
    private final BloodRequestRepository bloodRequests;
    private final DiagnosticReportRepository reports;
    private final TreatmentRepository treatments;
    private final AdmissionRepository admissions;
    private final InvoiceService invoiceService;
    private final NotificationService notifications;

    public AdmissionBillingService(BillingRepository billings, BloodRequestRepository bloodRequests,
                                   DiagnosticReportRepository reports, TreatmentRepository treatments,
                                   AdmissionRepository admissions, InvoiceService invoiceService,
                                   NotificationService notifications) {
        this.billings = billings;
        this.bloodRequests = bloodRequests;
        this.reports = reports;
        this.treatments = treatments;
        this.admissions = admissions;
        this.invoiceService = invoiceService;
        this.notifications = notifications;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** The live (non-cancelled) invoice of a stay, if any. */
    public Billing activeBill(Admission a) {
        for (Billing b : billings.findByAdmissionIdOrderByIdAsc(a.getId())) {
            if (!"Cancelled".equalsIgnoreCase(b.getStatus())) return b;
        }
        return null;
    }

    /** True when the stay's invoice predates auto-billing (typed-in lines, no "auto" keys). */
    private boolean isLegacy(Billing bill) {
        List<Map<String, Object>> items = invoiceService.parseList(bill.getItemsJson());
        if (items.isEmpty()) return false;
        for (Map<String, Object> it : items) if (it.get("auto") != null) return false;
        return true;
    }

    /** Put an issued blood request on the stay's invoice: as an auto line on an auto-managed invoice, or
     *  as one typed-style line appended to an older hand-made invoice. Leaves it unbilled if the stay
     *  has no live invoice yet (it is picked up when the invoice is opened). */
    @Transactional
    public void attachBlood(Admission a, BloodRequest br) {
        Billing bill = activeBill(a);
        if (bill == null) return;
        if (!isLegacy(bill)) { sync(a, false); return; }
        if (br.isBilled() || nz(br.getTotalCharge()).signum() <= 0) return;
        List<Map<String, Object>> items = invoiceService.parseList(bill.getItemsJson());
        Map<String, Object> it = InvoiceService.bloodItem(br);
        it.remove("ref");
        items.add(it);
        bill.setItemsJson(invoiceService.toJson(items));
        List<Map<String, Object>> refs = invoiceService.parseList(bill.getSourceRefsJson());
        refs.add(ref("BLOOD", br.getId()));
        bill.setSourceRefsJson(invoiceService.toJson(refs));
        invoiceService.recalculate(bill, false);
        billings.save(bill);
        br.setBilled(true);
        bloodRequests.save(br);
    }

    /** Refresh the stay's running invoice for one admitted patient (never creates one). */
    @Transactional
    public void syncActiveForPatient(Long patientId) {
        for (Admission a : admissions.findByPatientIdAndStatus(patientId, "Admitted")) sync(a, false);
    }

    /**
     * Rebuild the auto lines of an admission's invoice.
     * @param createIfMissing true on admit / discharge: open the invoice if the stay has none yet.
     */
    @Transactional
    public Billing sync(Admission a, boolean createIfMissing) {
        Billing bill = activeBill(a);
        if (bill == null && !createIfMissing) return null;
        if (bill != null && isLegacy(bill)) return bill;       // old hand-typed invoice: leave untouched

        if (a.getAdmittedOn() == null) a.setAdmittedOn(LocalDate.now());
        boolean discharged = "Discharged".equalsIgnoreCase(a.getStatus());
        LocalDate today = LocalDate.now();
        LocalDate end = discharged && a.getDischargedOn() != null ? a.getDischargedOn() : today;
        if (end.isBefore(a.getAdmittedOn())) end = a.getAdmittedOn();
        long days = Math.max(1, ChronoUnit.DAYS.between(a.getAdmittedOn(), end));

        if (bill == null) {
            bill = new Billing();
            bill.setPatient(a.getPatient());
            bill.setAdmission(a);
            bill.setBillDate(a.getAdmittedOn());
            bill.setDueDate((discharged ? end : today).plusDays(15));
            bill.setStatus("Unpaid");
            bill.setNotes("Inpatient bill - room, nursing, doctor visits, blood bank, tests and treatments are added automatically while the patient is admitted.");
        }
        if (a.getDoctor() != null) bill.setDoctor(a.getDoctor());
        if (discharged) bill.setDueDate(end.plusDays(15));

        // 1) keep the hand-typed lines, drop the previous auto lines
        List<Map<String, Object>> manual = new ArrayList<>();
        for (Map<String, Object> it : invoiceService.parseList(bill.getItemsJson())) {
            if (it.get("auto") == null) manual.add(it);
        }
        List<Map<String, Object>> auto = new ArrayList<>();
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Map<String, Object> r : invoiceService.parseList(bill.getSourceRefsJson())) {
            Object t = r.get("type");
            if (t == null || "ADMISSION".equals(t.toString())) continue;
            if (Set.of("REPORT", "TREATMENT", "TREATMENT_MEDS", "BLOOD").contains(t.toString())) continue; // rebuilt below
            refs.add(r);
        }

        // 2) room & bed, nursing, doctor visits
        BigDecimal rate = nz(a.getChargePerDay());
        String bed = (a.getWardType() == null ? "Room" : a.getWardType())
                + (a.getBedNumber() != null ? " - bed " + a.getBedNumber() : "");
        auto.add(InvoiceService.autoItem("Room & Bed", bed + " (" + a.getAdmittedOn() + " to "
                + (discharged ? end : "date") + ")", days, rate, 0, "ROOM", "ADMISSION", a.getId()));
        boolean icu = a.getWardType() != null && a.getWardType().toUpperCase().contains("ICU");
        auto.add(InvoiceService.autoItem("Nursing & Care", "Nursing & care charges", days,
                new BigDecimal(icu ? "1500" : "400"), 0, "NURSING", null, null));
        BigDecimal visit = a.getDoctor() != null && a.getDoctor().getConsultationFee() != null
                ? a.getDoctor().getConsultationFee().multiply(new BigDecimal("0.6")).setScale(0, RoundingMode.HALF_UP)
                : new BigDecimal("300");
        auto.add(InvoiceService.autoItem("Doctor Fees", "Daily doctor visits - "
                + (a.getDoctor() != null ? a.getDoctor().getName() : "attending"), days, visit, 0, "VISIT", null, null));
        refs.add(ref("ADMISSION", a.getId()));

        // 3) blood issued during the stay
        for (BloodRequest br : bloodRequests.findByAdmissionIdAndStatus(a.getId(), "Fulfilled")) {
            if (nz(br.getTotalCharge()).signum() <= 0) continue;
            Map<String, Object> it = InvoiceService.autoItem("Blood Bank", "Blood " + br.getBloodGroup()
                    + " (" + br.getFulfilledDate() + ")", br.getUnits() == null ? 1 : br.getUnits(),
                    nz(br.getPricePerUnit()), 0, "BLOOD:" + br.getId(), "BLOOD", br.getId());
            auto.add(it);
            refs.add(ref("BLOOD", br.getId()));
            if (!br.isBilled()) {
                br.setBilled(true);
                bloodRequests.save(br);
            }
        }

        // 4) diagnostic reports done during the stay (not already invoiced elsewhere)
        for (DiagnosticReport r : reports.findByPatientIdOrderByReportDateDescIdDesc(a.getPatient().getId())) {
            if (r.getReportDate() == null || r.getReportDate().isBefore(a.getAdmittedOn()) || r.getReportDate().isAfter(end)) continue;
            if (nz(r.getCost()).signum() <= 0) continue;
            boolean mine = alreadyOnThisBill(bill, "REPORT", r.getId());
            if (r.isBilled() && !mine) continue;
            auto.add(InvoiceService.autoItem(InvoiceService.reportCategoryOf(r),
                    (r.getTitle() != null ? r.getTitle() : r.getReportType()) + " (" + r.getReportDate() + ")",
                    1, r.getCost(), 0, "REPORT:" + r.getId(), "REPORT", r.getId()));
            refs.add(ref("REPORT", r.getId()));
            if (!r.isBilled()) {
                r.setBilled(true);
                reports.save(r);
            }
        }

        // 5) procedures / OT / medicines of this stay's treatments
        for (Treatment t : treatments.findByPatientIdOrderByStartDateDescIdDesc(a.getPatient().getId())) {
            if (t.getAdmissionId() == null || !t.getAdmissionId().equals(a.getId())) continue;
            boolean mine = alreadyOnThisBill(bill, "TREATMENT", t.getId());
            if (t.isBilled() && !mine) continue;
            boolean any = false;
            if (nz(t.getProcedureCost()).signum() > 0) {
                String name = t.getProcedureName() != null && !t.getProcedureName().isBlank() ? t.getProcedureName() : t.getTreatmentType();
                auto.add(InvoiceService.autoItem("Procedures & Surgery", name + " - " + t.getDiagnosis(), 1,
                        t.getProcedureCost(), 0, "TREATMENT:" + t.getId(), "TREATMENT", t.getId()));
                if ("Surgery".equalsIgnoreCase(t.getTreatmentType())) {
                    BigDecimal ot = t.getProcedureCost().multiply(new BigDecimal("0.15")).setScale(0, RoundingMode.HALF_UP);
                    auto.add(InvoiceService.autoItem("Operation Theatre", "OT charges - " + name, 1, ot, 0,
                            "OT:" + t.getId(), null, null));
                }
                refs.add(ref("TREATMENT", t.getId()));
                any = true;
            }
            if (nz(t.getMedicationCost()).signum() > 0) {
                auto.add(InvoiceService.autoItem("Pharmacy", "Medicines - " + t.getDiagnosis(), 1,
                        t.getMedicationCost(), 5, "MEDS:" + t.getId(), "TREATMENT_MEDS", t.getId()));
                refs.add(ref("TREATMENT_MEDS", t.getId()));
                any = true;
            }
            if (any && !t.isBilled()) {
                t.setBilled(true);
                treatments.save(t);
            }
        }

        List<Map<String, Object>> all = new ArrayList<>(manual);
        all.addAll(auto);
        bill.setItemsJson(invoiceService.toJson(stripRefs(all)));
        bill.setSourceRefsJson(invoiceService.toJson(refs));
        if (bill.getId() == null) bill = billings.save(bill);      // need the id so cover can be looked up
        invoiceService.recalculate(bill, false);
        bill = billings.save(bill);

        a.setBilledUpTo(end);
        if (discharged) a.setBilled(true);
        admissions.save(a);
        return bill;
    }

    /** Called on discharge: finalise the invoice and tell the patient what is due. */
    @Transactional
    public Billing finalizeOnDischarge(Admission a) {
        Billing bill = sync(a, true);
        if (bill != null && a.getPatient() != null) {
            notifications.notifyUser(a.getPatient().getUser(), "Your final bill is ready",
                    "Invoice " + InvoiceService.invoiceNo(bill) + " for your stay is ₹" + bill.getAmount()
                            + ". Insurance / scheme cover is deducted once it is sanctioned.",
                    "BILLING", "invoice.html?id=" + bill.getId());
        }
        return bill;
    }

    /** Give a cancelled / deleted invoice's records back so they can be billed again. */
    @Transactional
    public void release(Billing bill) {
        for (Map<String, Object> r : invoiceService.parseList(bill.getSourceRefsJson())) {
            if (r.get("type") == null || r.get("id") == null) continue;
            Long id;
            try { id = Long.valueOf(r.get("id").toString()); } catch (NumberFormatException e) { continue; }
            switch (r.get("type").toString()) {
                case "REPORT" -> reports.findById(id).ifPresent(x -> { x.setBilled(false); reports.save(x); });
                case "TREATMENT", "TREATMENT_MEDS" -> treatments.findById(id).ifPresent(x -> { x.setBilled(false); treatments.save(x); });
                case "BLOOD" -> bloodRequests.findById(id).ifPresent(x -> { x.setBilled(false); bloodRequests.save(x); });
                default -> { }
            }
        }
        if (bill.getAdmission() != null) {
            Admission a = bill.getAdmission();
            a.setBilled(false);
            a.setBilledUpTo(null);
            admissions.save(a);
        }
    }

    private boolean alreadyOnThisBill(Billing bill, String type, Long id) {
        for (Map<String, Object> r : invoiceService.parseList(bill.getSourceRefsJson())) {
            if (type.equals(String.valueOf(r.get("type"))) && String.valueOf(id).equals(String.valueOf(r.get("id")))) return true;
        }
        return false;
    }

    private static Map<String, Object> ref(String type, Long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        return m;
    }

    private static List<Map<String, Object>> stripRefs(List<Map<String, Object>> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> c = new LinkedHashMap<>(it);
            c.remove("ref");
            out.add(c);
        }
        return out;
    }
}
