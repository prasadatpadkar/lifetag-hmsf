package com.lifetag.hms.controller;

import com.lifetag.hms.entity.BloodBank;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.BloodBankRepository;
import com.lifetag.hms.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/blood-bank")
public class BloodBankController {

    @Autowired
    private BloodBankRepository bloodBankRepository;
    @Autowired
    private NotificationService notificationService;

    /** Same threshold the seed data / frontend already treat as "Low Stock". */
    private static final int LOW_STOCK_THRESHOLD = 5;

    @GetMapping
    public List<BloodBank> getAll() {
        return bloodBankRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<BloodBank> getById(@PathVariable Long id) {
        return bloodBankRepository.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public BloodBank create(@Valid @RequestBody BloodBank entry) {
        entry.setId(null);
        if (entry.getPricePerUnit() == null) entry.setPricePerUnit(com.lifetag.hms.service.BloodStockService.DEFAULT_PRICE);
        if (entry.getExpiryDate() == null) {                      // whole blood keeps about 35 days
            java.time.LocalDate from = entry.getLastDonationDate() != null ? entry.getLastDonationDate() : java.time.LocalDate.now();
            entry.setExpiryDate(from.plusDays(35));
        }
        entry.setStatus(statusOf(entry));
        BloodBank saved = bloodBankRepository.save(entry);
        notifyIfLowStock(saved);
        return saved;
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<BloodBank> update(@PathVariable Long id, @Valid @RequestBody BloodBank updated) {
        return bloodBankRepository.findById(id).map(existing -> {
            existing.setBloodGroup(updated.getBloodGroup());
            existing.setUnitsAvailable(updated.getUnitsAvailable());
            existing.setDonorName(updated.getDonorName());
            existing.setDonorContact(updated.getDonorContact());
            existing.setLastDonationDate(updated.getLastDonationDate());
            existing.setExpiryDate(updated.getExpiryDate() != null ? updated.getExpiryDate() : existing.getExpiryDate());
            if (updated.getPricePerUnit() != null) existing.setPricePerUnit(updated.getPricePerUnit());
            existing.setStatus(statusOf(existing));
            BloodBank saved = bloodBankRepository.save(existing);
            notifyIfLowStock(saved);
            return ResponseEntity.ok(saved);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Stock status is derived, never typed: Expired / Out of Stock / Low Stock / Available. */
    private static String statusOf(BloodBank b) {
        if (b.getExpiryDate() != null && b.getExpiryDate().isBefore(java.time.LocalDate.now())) return "Expired";
        return com.lifetag.hms.service.BloodStockService.statusFor(b.getUnitsAvailable() == null ? 0 : b.getUnitsAvailable());
    }

    private void notifyIfLowStock(BloodBank entry) {
        if (entry.getUnitsAvailable() != null && entry.getUnitsAvailable() < LOW_STOCK_THRESHOLD) {
            notificationService.notifyRole(Role.ADMIN, "Low blood stock",
                    entry.getBloodGroup() + " is down to " + entry.getUnitsAvailable() + " unit(s).",
                    "BLOOD", "blood-bank-management.html");
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!bloodBankRepository.existsById(id)) return ResponseEntity.notFound().build();
        bloodBankRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
