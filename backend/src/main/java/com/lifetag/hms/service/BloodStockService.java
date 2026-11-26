package com.lifetag.hms.service;

import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.BloodBankRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Issues blood from the bank to a patient: checks stock, deducts the units (oldest expiry first),
 *  prices the request and attaches it to the patient's current admission so it is billed. */
@Service
public class BloodStockService {

    public static final Set<String> GROUPS = Set.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-");
    public static final int LOW_STOCK_THRESHOLD = 5;
    public static final BigDecimal DEFAULT_PRICE = new BigDecimal("1500.00");

    private final BloodBankRepository bank;
    private final AdmissionService admissionService;
    private final NotificationService notifications;

    public BloodStockService(BloodBankRepository bank, AdmissionService admissionService, NotificationService notifications) {
        this.bank = bank;
        this.admissionService = admissionService;
        this.notifications = notifications;
    }

    private static boolean usable(BloodBank b, String group) {
        return b.getBloodGroup() != null && b.getBloodGroup().equalsIgnoreCase(group)
                && b.getUnitsAvailable() != null && b.getUnitsAvailable() > 0
                && (b.getExpiryDate() == null || !b.getExpiryDate().isBefore(LocalDate.now()))
                && !"Expired".equalsIgnoreCase(b.getStatus());
    }

    /** Non-expired units of a group currently in stock. */
    public int unitsInStock(String group) {
        int sum = 0;
        for (BloodBank b : bank.findAll()) if (usable(b, group)) sum += b.getUnitsAvailable();
        return sum;
    }

    public static String statusFor(int units) {
        return units <= 0 ? "Out of Stock" : units < LOW_STOCK_THRESHOLD ? "Low Stock" : "Available";
    }

    /** Deduct stock and price the request. Throws IllegalArgumentException if there is not enough blood. */
    @Transactional
    public void issue(BloodRequest r) {
        int need = r.getUnits() == null ? 1 : r.getUnits();
        List<BloodBank> stock = new ArrayList<>();
        int have = 0;
        for (BloodBank b : bank.findAll()) {
            if (usable(b, r.getBloodGroup())) { stock.add(b); have += b.getUnitsAvailable(); }
        }
        if (have < need) {
            throw new IllegalArgumentException("Only " + have + " unit(s) of " + r.getBloodGroup()
                    + " are in stock (non-expired); " + need + " requested.");
        }
        stock.sort(Comparator.comparing(BloodBank::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder())));
        BigDecimal price = null;
        int left = need;
        for (BloodBank b : stock) {
            if (left == 0) break;
            int take = Math.min(left, b.getUnitsAvailable());
            b.setUnitsAvailable(b.getUnitsAvailable() - take);
            b.setStatus(statusFor(b.getUnitsAvailable()));
            bank.save(b);
            if (price == null) price = b.getPricePerUnit();
            left -= take;
        }
        if (price == null) price = DEFAULT_PRICE;
        r.setPricePerUnit(price);
        r.setTotalCharge(price.multiply(BigDecimal.valueOf(need)));
        r.setFulfilledDate(LocalDate.now());
        r.setBilled(false);
        Admission adm = admissionService.activeAdmission(r.getPatient().getId());
        r.setAdmission(adm);

        int remaining = unitsInStock(r.getBloodGroup());
        if (remaining < LOW_STOCK_THRESHOLD) {
            notifications.notifyRole(Role.ADMIN, "Low blood stock", r.getBloodGroup() + " is down to " + remaining
                    + " unit(s).", "BLOOD", "blood-bank-management.html");
        }
    }
}
