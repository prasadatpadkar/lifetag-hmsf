package com.lifetag.hms.controller;

import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Aggregated stats consumed by the admin/reports dashboards. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private BillingRepository billingRepository;
    @Autowired private RoomBedRepository roomBedRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private com.lifetag.hms.service.InvoiceService invoiceService;

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPatients", patientRepository.count());
        summary.put("totalDoctors", doctorRepository.count());
        summary.put("totalReceptionists", userRepository.findByRole(Role.RECEPTION).size());
        summary.put("totalAdmins", userRepository.findByRole(Role.ADMIN).size());
        summary.put("totalAppointments", appointmentRepository.count());
        summary.put("totalBeds", roomBedRepository.count());
        summary.put("occupiedBeds", roomBedRepository.findAll().stream()
                .filter(r -> "Occupied".equalsIgnoreCase(r.getStatus())).count());
        summary.put("totalBloodUnits", bloodBankRepository.findAll().stream()
                .mapToInt(b -> b.getUnitsAvailable() == null ? 0 : b.getUnitsAvailable()).sum());
        BigDecimal revenue = billingRepository.findAll().stream()
                .map(b -> b.getAmountPaid() == null ? BigDecimal.ZERO : b.getAmountPaid())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        summary.put("totalRevenueCollected", revenue);
        BigDecimal pending = billingRepository.findAll().stream()
                .filter(b -> !"Paid".equalsIgnoreCase(b.getStatus()) && !"Cancelled".equalsIgnoreCase(b.getStatus()))
                .map(b -> {
                    BigDecimal amount = b.getAmount() == null ? BigDecimal.ZERO : b.getAmount();
                    BigDecimal covered = b.getInsuranceCovered() == null ? BigDecimal.ZERO : b.getInsuranceCovered();
                    BigDecimal paid = b.getAmountPaid() == null ? BigDecimal.ZERO : b.getAmountPaid();
                    return amount.subtract(covered).subtract(paid).max(BigDecimal.ZERO);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        summary.put("totalPendingBilling", pending);
        return summary;
    }

    /** Where the hospital's billed revenue comes from: total per spending category
     *  (doctor fees, room & bed, pharmacy, ...) across every non-cancelled invoice. */
    @GetMapping("/revenue-by-category")
    public Map<String, Object> revenueByCategory() {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        BigDecimal grand = BigDecimal.ZERO;
        for (com.lifetag.hms.entity.Billing b : billingRepository.findAll()) {
            if ("Cancelled".equalsIgnoreCase(b.getStatus())) continue;
            for (Map<String, Object> it : invoiceService.lineItems(b)) {
                BigDecimal t = (BigDecimal) it.get("lineTotal");
                totals.merge((String) it.get("category"), t, BigDecimal::add);
                grand = grand.add(t);
            }
        }
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : totals.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", e.getKey());
            row.put("amount", e.getValue());
            row.put("percent", grand.signum() == 0 ? BigDecimal.ZERO
                    : e.getValue().multiply(new BigDecimal("100")).divide(grand, 1, java.math.RoundingMode.HALF_UP));
            rows.add(row);
        }
        rows.sort((a, c) -> ((BigDecimal) c.get("amount")).compareTo((BigDecimal) a.get("amount")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", grand);
        out.put("categories", rows);
        return out;
    }
}
