package com.lifetag.hms.config;

import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.*;
import com.lifetag.hms.service.AdmissionBillingService;
import com.lifetag.hms.service.BillingSyncService;
import com.lifetag.hms.service.BloodStockService;
import com.lifetag.hms.service.InvoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * Brings existing data (demo seed or a database created before auto-billing) in line with the
 * billing rules. Safe to run on every start - every step only touches records that are still wrong:
 *
 *  1. schemes get their fixed cover ceiling; blood bank rows get a price per unit
 *  2. a bed marked Occupied with no admission behind it gets one (so its room charge is billed)
 *  3. blood already issued (Fulfilled) but never priced / charged is priced and linked to the stay it fell in
 *  4. every open stay (and every discharged-but-never-invoiced stay) gets its running invoice
 *  5. insurance / scheme cover on every invoice is recomputed from SANCTIONED claims only
 *     (earlier data deducted cover for claims that were only submitted or under review)
 */
@Component
@Order(20)
public class BillingDataMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BillingDataMigrator.class);

    private final SchemeRepository schemes;
    private final BloodBankRepository bloodBank;
    private final BloodRequestRepository bloodRequests;
    private final RoomBedRepository beds;
    private final AdmissionRepository admissions;
    private final BillingRepository billings;
    private final InsuranceClaimRepository insuranceClaims;
    private final SchemeClaimRepository schemeClaims;
    private final AdmissionBillingService admissionBilling;
    private final BillingSyncService billingSync;
    private final InvoiceService invoiceService;
    private final PlatformTransactionManager txManager;

    public BillingDataMigrator(SchemeRepository schemes, BloodBankRepository bloodBank, BloodRequestRepository bloodRequests,
                               RoomBedRepository beds, AdmissionRepository admissions, BillingRepository billings,
                               InsuranceClaimRepository insuranceClaims, SchemeClaimRepository schemeClaims,
                               AdmissionBillingService admissionBilling, BillingSyncService billingSync,
                               InvoiceService invoiceService, PlatformTransactionManager txManager) {
        this.schemes = schemes;
        this.bloodBank = bloodBank;
        this.bloodRequests = bloodRequests;
        this.beds = beds;
        this.admissions = admissions;
        this.billings = billings;
        this.insuranceClaims = insuranceClaims;
        this.schemeClaims = schemeClaims;
        this.admissionBilling = admissionBilling;
        this.billingSync = billingSync;
        this.invoiceService = invoiceService;
        this.txManager = txManager;
    }

    @Override
    public void run(String... args) {
        AuditEventListener.suspended = true;
        try {
            new TransactionTemplate(txManager).executeWithoutResult(s -> migrate());
        } catch (Exception e) {
            log.error("Billing data migration failed - rolled back; the app continues with the data as it was.", e);
        } finally {
            AuditEventListener.suspended = false;
        }
    }

    private void migrate() {
        // 1) fixed ceilings and prices
        int fixed = 0;
        for (Scheme s : schemes.findAll()) {
            if (s.getMaxCoverAmount() != null || s.getName() == null) continue;
            String n = s.getName().toLowerCase();
            if (n.contains("ayushman") || n.contains("jan arogya") || n.contains("mjpjay") || n.contains("pmjay")) {
                s.setMaxCoverAmount(new BigDecimal("500000"));
                schemes.save(s);
                fixed++;
            }
        }
        for (BloodBank b : bloodBank.findAll()) {
            if (b.getPricePerUnit() == null) {
                b.setPricePerUnit(BloodStockService.DEFAULT_PRICE);
                bloodBank.save(b);
                fixed++;
            }
        }

        // 2) occupied beds that have no admission behind them
        int opened = 0;
        for (RoomBed bed : beds.findAll()) {
            if (!"Occupied".equalsIgnoreCase(bed.getStatus()) || bed.getPatient() == null) continue;
            boolean has = !admissions.findByPatientIdAndStatus(bed.getPatient().getId(), "Admitted").isEmpty();
            if (has) continue;
            Admission a = new Admission();
            a.setPatient(bed.getPatient());
            a.setRoomBed(bed);
            a.setWardType(bed.getRoomType());
            a.setRoomNumber(bed.getRoomNumber());
            a.setBedNumber(bed.getBedNumber());
            a.setChargePerDay(bed.getChargePerDay() == null ? BigDecimal.ZERO : BigDecimal.valueOf(bed.getChargePerDay()));
            a.setAdmittedOn(java.time.LocalDate.now());
            a.setStatus("Admitted");
            a.setReason("Admitted (bed allotted before admissions were tracked)");
            admissions.save(a);
            opened++;
        }

        // 3) blood issued earlier but never charged
        int blood = 0;
        for (BloodRequest r : bloodRequests.findAll()) {
            if (!"Fulfilled".equals(r.getStatus())) continue;
            if (r.getTotalCharge() != null && r.getTotalCharge().signum() > 0) continue;
            BigDecimal price = BloodStockService.DEFAULT_PRICE;
            for (BloodBank b : bloodBank.findAll()) {
                if (b.getBloodGroup() != null && b.getBloodGroup().equalsIgnoreCase(r.getBloodGroup()) && b.getPricePerUnit() != null) {
                    price = b.getPricePerUnit();
                    break;
                }
            }
            r.setPricePerUnit(price);
            r.setTotalCharge(price.multiply(BigDecimal.valueOf(r.getUnits() == null ? 1 : r.getUnits())));
            if (r.getFulfilledDate() == null) r.setFulfilledDate(r.getRequestDate());
            if (r.getAdmission() == null && r.getPatient() != null) {
                java.time.LocalDate d = r.getFulfilledDate();
                for (Admission a : admissions.findByPatientIdOrderByAdmittedOnDescIdDesc(r.getPatient().getId())) {
                    boolean after = a.getAdmittedOn() != null && !a.getAdmittedOn().isAfter(d);
                    boolean before = a.getDischargedOn() == null || !a.getDischargedOn().isBefore(d);
                    if (after && before) { r.setAdmission(a); break; }
                }
            }
            r.setBilled(false);
            bloodRequests.save(r);
            blood++;
        }

        // 4) running / missing inpatient invoices
        int invoices = 0;
        for (Admission a : admissions.findAll()) {
            Billing live = admissionBilling.activeBill(a);
            if (live == null && (!a.isBilled() || "Admitted".equals(a.getStatus()))) {
                admissionBilling.sync(a, true);
                invoices++;
            } else if (live != null) {
                // attach blood that was issued during a stay whose invoice already exists
                for (BloodRequest r : bloodRequests.findByAdmissionIdAndStatus(a.getId(), "Fulfilled")) {
                    if (!r.isBilled()) admissionBilling.attachBlood(a, r);
                }
            }
        }

        // 5) cover only from sanctioned claims
        Set<Long> touched = new HashSet<>();
        int repriced = 0;
        for (InsuranceClaim c : insuranceClaims.findAll()) {
            Long id = com.lifetag.hms.service.CoverageService.parseInvoiceId(c.getInvoiceRef());
            if (id != null) touched.add(id);
        }
        for (SchemeClaim c : schemeClaims.findAll()) {
            Long id = com.lifetag.hms.service.CoverageService.parseInvoiceId(c.getInvoiceRef());
            if (id != null) touched.add(id);
        }
        for (Billing b : billings.findAll()) {
            boolean hadCover = b.getInsuranceCovered() != null && b.getInsuranceCovered().signum() > 0;
            if (touched.contains(b.getId()) || hadCover) {
                invoiceService.recalculate(b, false);
                billings.save(b);
                repriced++;
            }
        }
        if (fixed + opened + blood + invoices + repriced > 0) {
            log.info("Billing data aligned: {} defaults set, {} admissions opened for occupied beds, {} blood charges priced, "
                    + "{} inpatient invoices opened, {} invoices re-priced from sanctioned cover.", fixed, opened, blood, invoices, repriced);
        }
    }
}
