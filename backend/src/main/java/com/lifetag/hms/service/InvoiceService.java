package com.lifetag.hms.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifetag.hms.entity.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * All invoice arithmetic lives here so the server - not the browser - decides what a bill
 * adds up to: line totals, tax, the grand total, what is still owed and the Paid / Partial /
 * Unpaid status. It also builds the "where did the money go" category breakdown shown on the
 * printable invoice.
 */
@Service
public class InvoiceService {

    /** Fixed set of spending categories offered on the invoice form (and used for the breakdown). */
    public static final List<String> CATEGORIES = List.of(
            "Doctor Fees", "Room & Bed", "Nursing & Care", "Radiology", "Laboratory",
            "Cardiac Diagnostics", "Special Investigations", "Procedures & Surgery", "Operation Theatre", "Pharmacy",
            "Blood Bank", "Ambulance", "Registration", "Miscellaneous");

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final CoverageService coverage;

    @Value("${app.hospital.name:LifeTag Multispeciality Hospital}")
    private String hospitalName;
    @Value("${app.hospital.address:12 Health Avenue, Sangli, Maharashtra 416416}")
    private String hospitalAddress;
    @Value("${app.hospital.phone:+91 233 000 0000}")
    private String hospitalPhone;
    @Value("${app.hospital.email:billing@lifetag.com}")
    private String hospitalEmail;
    @Value("${app.hospital.gstin:27ABCDE1234F1Z5}")
    private String hospitalGstin;

    public InvoiceService(ObjectMapper mapper, CoverageService coverage) {
        this.mapper = mapper;
        this.coverage = coverage;
    }

    // ------------------------------------------------------------------ helpers

    public static BigDecimal money(Object v) {
        if (v == null) return BigDecimal.ZERO;
        String s = v.toString().trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not a valid amount.");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<Map<String, Object>> list = mapper.readValue(json, LIST_OF_MAPS);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not encode the data.");
        }
    }

    /** Normalised line items: category, desc, qty, rate, taxPct, lineAmount, taxAmount, lineTotal. */
    public List<Map<String, Object>> lineItems(Billing b) {
        List<Map<String, Object>> raw = parseList(b.getItemsJson());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            BigDecimal qty = r.get("qty") == null ? BigDecimal.ONE : money(r.get("qty"));
            if (qty.signum() <= 0) qty = BigDecimal.ONE;
            BigDecimal rate = money(r.get("amount") != null ? r.get("amount") : r.get("rate"));
            BigDecimal taxPct = money(r.get("taxPct"));
            BigDecimal line = qty.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax = line.multiply(taxPct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            Map<String, Object> item = new LinkedHashMap<>();
            Object cat = r.get("category");
            item.put("category", cat == null || cat.toString().isBlank() ? "Miscellaneous" : cat.toString());
            Object desc = r.get("desc");
            item.put("desc", desc == null ? "Charge" : desc.toString());
            Object auto = r.get("auto");
            if (auto != null && !auto.toString().isBlank()) item.put("auto", auto.toString());
            Object note = r.get("note");
            if (note != null && !note.toString().isBlank()) item.put("note", note.toString());
            item.put("qty", qty.stripTrailingZeros().toPlainString());
            item.put("rate", rate);
            item.put("taxPct", taxPct);
            item.put("lineAmount", line);
            item.put("taxAmount", tax);
            item.put("lineTotal", line.add(tax));
            out.add(item);
        }
        return out;
    }

    // ------------------------------------------------------------------ recalculation

    /** Recomputes subtotal / tax / grand total from the line items (when there are any) and
     *  derives the payment status. Safe to call after every create / update / payment. */
    public void recalculate(Billing b, boolean markPaidIfStatusPaid) {
        List<Map<String, Object>> items = lineItems(b);
        BigDecimal discount = nz(b.getDiscount());
        if (!items.isEmpty()) {
            BigDecimal subtotal = BigDecimal.ZERO;
            BigDecimal tax = BigDecimal.ZERO;
            for (Map<String, Object> it : items) {
                subtotal = subtotal.add((BigDecimal) it.get("lineAmount"));
                tax = tax.add((BigDecimal) it.get("taxAmount"));
            }
            if (discount.compareTo(subtotal) > 0) {
                throw new IllegalArgumentException("Discount cannot be more than the subtotal.");
            }
            b.setSubtotal(subtotal);
            b.setTaxAmount(tax);
            b.setAmount(subtotal.subtract(discount).add(tax).max(BigDecimal.ZERO));
            if (b.getDescription() == null || b.getDescription().isBlank()) {
                LinkedHashSet<String> cats = new LinkedHashSet<>();
                for (Map<String, Object> it : items) cats.add((String) it.get("category"));
                String d = String.join(", ", cats);
                b.setDescription(d.length() > 250 ? d.substring(0, 250) : d);
            }
        } else if (b.getSubtotal() == null) {
            b.setSubtotal(nz(b.getAmount()));
        }

        BigDecimal amount = nz(b.getAmount());
        // Cover is NEVER typed in by hand: it is whatever insurers / schemes have actually SANCTIONED
        // against this invoice. Claims that are only submitted or under review are reported as
        // "awaiting sanction" and are not deducted.
        BigDecimal covered = BigDecimal.ZERO;
        if (b.getId() != null) {
            CoverageService.Result cr = coverage.compute(invoiceNo(b), amount);
            covered = cr.sanctioned;
            b.setCoverageJson(cr.lines.isEmpty() ? null : toJson(cr.lines));
            b.setCoveragePending(cr.pending);
            b.setInsuranceNote(cr.note.isEmpty() ? null : cr.note);
        } else {
            b.setCoverageJson(null);
            b.setCoveragePending(BigDecimal.ZERO);
            b.setInsuranceNote(null);
        }
        b.setInsuranceCovered(covered);
        BigDecimal payable = amount.subtract(covered).max(BigDecimal.ZERO);
        BigDecimal paid = nz(b.getAmountPaid());
        if (markPaidIfStatusPaid && "Paid".equalsIgnoreCase(b.getStatus()) && paid.compareTo(payable) < 0) {
            paid = payable;
            b.setAmountPaid(paid);
        }
        // The patient may have paid more than they finally owe (e.g. paid in full, then a claim was
        // sanctioned). Keep the money on record and flag the excess for refund / adjustment.
        b.setRefundDue(paid.compareTo(payable) > 0 ? paid.subtract(payable) : BigDecimal.ZERO);
        if (!"Cancelled".equalsIgnoreCase(b.getStatus())) {
            if (paid.compareTo(payable) >= 0) b.setStatus("Paid");
            else if (paid.signum() > 0) b.setStatus("Partial");
            else b.setStatus("Unpaid");
        }
    }

    // ------------------------------------------------------------------ invoice view

    /** "Where did the money go": amount (incl. tax) per category with its share of the total. */
    public List<Map<String, Object>> categorySummary(List<Map<String, Object>> items) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        BigDecimal grand = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            String cat = (String) it.get("category");
            BigDecimal t = (BigDecimal) it.get("lineTotal");
            totals.merge(cat, t, BigDecimal::add);
            counts.merge(cat, 1, Integer::sum);
            grand = grand.add(t);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : totals.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", e.getKey());
            row.put("amount", e.getValue());
            row.put("items", counts.get(e.getKey()));
            BigDecimal pct = grand.signum() == 0 ? BigDecimal.ZERO
                    : e.getValue().multiply(new BigDecimal("100")).divide(grand, 1, RoundingMode.HALF_UP);
            row.put("percent", pct);
            out.add(row);
        }
        out.sort((a, c) -> ((BigDecimal) c.get("amount")).compareTo((BigDecimal) a.get("amount")));
        return out;
    }

    public Map<String, Object> buildInvoice(Billing b) {
        List<Map<String, Object>> items = lineItems(b);
        BigDecimal amount = nz(b.getAmount());
        BigDecimal covered = nz(b.getInsuranceCovered());
        BigDecimal payable = amount.subtract(covered).max(BigDecimal.ZERO);
        BigDecimal paid = nz(b.getAmountPaid());
        BigDecimal pending = nz(b.getCoveragePending());
        BigDecimal balance = payable.subtract(paid).max(BigDecimal.ZERO);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("subtotal", b.getSubtotal() != null ? b.getSubtotal() : amount);
        totals.put("discount", nz(b.getDiscount()));
        totals.put("tax", nz(b.getTaxAmount()));
        totals.put("grandTotal", amount);
        totals.put("insuranceCovered", covered);
        totals.put("patientPayable", payable);
        totals.put("paid", paid);
        totals.put("balance", balance);
        // claimed but not yet sanctioned: shown to everyone, NOT deducted until sanctioned
        totals.put("coveragePending", pending);
        totals.put("expectedBalanceIfSanctioned", balance.subtract(pending).max(BigDecimal.ZERO));
        totals.put("refundDue", nz(b.getRefundDue()));

        Map<String, Object> hospital = new LinkedHashMap<>();
        hospital.put("name", hospitalName);
        hospital.put("address", hospitalAddress);
        hospital.put("phone", hospitalPhone);
        hospital.put("email", hospitalEmail);
        hospital.put("gstin", hospitalGstin);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoiceNo", invoiceNo(b));
        out.put("bill", b);
        out.put("items", items);
        out.put("categorySummary", categorySummary(items));
        out.put("totals", totals);
        out.put("payments", parseList(b.getPaymentsJson()));
        out.put("coverage", parseList(b.getCoverageJson()));
        out.put("hospital", hospital);
        return out;
    }

    public static String invoiceNo(Billing b) {
        return String.format("INV-%04d", b.getId() == null ? 0 : b.getId());
    }

    // ------------------------------------------------------------------ suggested charges

    private static Map<String, Object> item(String category, String desc, long qty, BigDecimal rate, int taxPct,
                                            String refType, Long refId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", category);
        m.put("desc", desc);
        m.put("qty", qty);
        m.put("amount", rate);
        m.put("taxPct", taxPct);
        if (refType != null) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("type", refType);
            ref.put("id", refId);
            m.put("ref", ref);
        }
        return m;
    }

    private static String reportCategory(DiagnosticReport r) {
        String c = r.getCategory() == null ? "" : r.getCategory();
        if (c.equalsIgnoreCase("Radiology")) return "Radiology";
        if (c.equalsIgnoreCase("Cardiac")) return "Cardiac Diagnostics";
        if (c.equalsIgnoreCase("Special Tests")) return "Special Investigations";
        return "Laboratory";
    }

    /** Charges that exist in the patient's clinical record but have not been invoiced yet. */
    public List<Map<String, Object>> suggestCharges(List<Appointment> appointments, List<Admission> admissions,
                                                    List<DiagnosticReport> reports, List<Treatment> treatments,
                                                    List<BloodRequest> bloodRequests) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Appointment a : appointments) {
            BigDecimal fee = a.getDoctor() != null && a.getDoctor().getConsultationFee() != null
                    ? a.getDoctor().getConsultationFee() : new BigDecimal("500");
            String doc = a.getDoctor() != null ? a.getDoctor().getName() : "doctor";
            out.add(item("Doctor Fees", "Consultation - " + doc + " (" + a.getAppointmentDate() + ")", 1, fee, 0,
                    "APPOINTMENT", a.getId()));
        }
        for (Admission ad : admissions) {
            long days = ad.unbilledDays(ad.getDischargedOn() != null ? ad.getDischargedOn() : java.time.LocalDate.now());
            if (days <= 0) continue;
            BigDecimal rate = nz(ad.getChargePerDay());
            String bed = (ad.getWardType() == null ? "Room" : ad.getWardType())
                    + (ad.getBedNumber() != null ? " - bed " + ad.getBedNumber() : "");
            out.add(item("Room & Bed", bed + " (" + ad.getAdmittedOn() + " to "
                    + (ad.getDischargedOn() != null ? ad.getDischargedOn() : "date") + ")", days, rate, 0,
                    "ADMISSION", ad.getId()));
            boolean icu = ad.getWardType() != null && ad.getWardType().toUpperCase().contains("ICU");
            out.add(item("Nursing & Care", "Nursing & care charges", days, new BigDecimal(icu ? "1500" : "400"), 0,
                    null, null));
            BigDecimal visit = ad.getDoctor() != null && ad.getDoctor().getConsultationFee() != null
                    ? ad.getDoctor().getConsultationFee().multiply(new BigDecimal("0.6")).setScale(0, RoundingMode.HALF_UP)
                    : new BigDecimal("300");
            out.add(item("Doctor Fees", "Daily doctor visits - " + (ad.getDoctor() != null ? ad.getDoctor().getName() : "attending"),
                    days, visit, 0, null, null));
        }
        for (DiagnosticReport r : reports) {
            if (nz(r.getCost()).signum() <= 0) continue;
            out.add(item(reportCategory(r), (r.getTitle() != null ? r.getTitle() : r.getReportType())
                    + " (" + r.getReportDate() + ")", 1, r.getCost(), 0, "REPORT", r.getId()));
        }
        for (Treatment t : treatments) {
            if (nz(t.getProcedureCost()).signum() > 0) {
                String name = t.getProcedureName() != null && !t.getProcedureName().isBlank() ? t.getProcedureName() : t.getTreatmentType();
                boolean surgery = "Surgery".equalsIgnoreCase(t.getTreatmentType());
                out.add(item("Procedures & Surgery", name + " - " + t.getDiagnosis(), 1, t.getProcedureCost(), 0,
                        "TREATMENT", t.getId()));
                if (surgery) {
                    BigDecimal ot = t.getProcedureCost().multiply(new BigDecimal("0.15")).setScale(0, RoundingMode.HALF_UP);
                    out.add(item("Operation Theatre", "OT charges - " + name, 1, ot, 0, null, null));
                }
            }
            if (nz(t.getMedicationCost()).signum() > 0) {
                out.add(item("Pharmacy", "Medicines - " + t.getDiagnosis(), 1, t.getMedicationCost(), 5,
                        "TREATMENT_MEDS", t.getId()));
            }
        }
        for (BloodRequest br : bloodRequests) {
            if (nz(br.getTotalCharge()).signum() <= 0) continue;
            out.add(bloodItem(br));
        }
        return out;
    }

    /** Line item for blood issued from the blood bank to a patient. */
    public static Map<String, Object> bloodItem(BloodRequest br) {
        return item("Blood Bank", "Blood " + br.getBloodGroup() + " (" + br.getFulfilledDate() + ")",
                br.getUnits() == null ? 1 : br.getUnits(), nz(br.getPricePerUnit()), 0, "BLOOD", br.getId());
    }

    /** Package-visible factory used by the inpatient auto-billing service. */
    public static Map<String, Object> autoItem(String category, String desc, long qty, BigDecimal rate, int taxPct,
                                               String autoKey, String refType, Long refId) {
        Map<String, Object> m = item(category, desc, qty, rate, taxPct, refType, refId);
        m.put("auto", autoKey);
        return m;
    }

    public static String reportCategoryOf(DiagnosticReport r) {
        return reportCategory(r);
    }
}
