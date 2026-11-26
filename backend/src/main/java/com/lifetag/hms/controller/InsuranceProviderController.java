package com.lifetag.hms.controller;

import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.InsuranceProvider;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.InsuranceProviderRepository;
import com.lifetag.hms.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/insurance-providers")
public class InsuranceProviderController {

    @Autowired
    private InsuranceProviderRepository insuranceProviderRepository;

    private boolean isAdmin(UserPrincipal principal) {
        return principal != null && principal.getUser().getRole() == Role.ADMIN;
    }

    /** Anyone signed in can read the directory (Reception needs it to file a policy). */
    @GetMapping
    public List<InsuranceProvider> getAll() {
        return insuranceProviderRepository.findAll();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@Valid @RequestBody InsuranceProvider provider, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can manage insurance providers."));
        }
        return ResponseEntity.ok(insuranceProviderRepository.save(provider));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody InsuranceProvider updated, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can manage insurance providers."));
        }
        InsuranceProvider existing = insuranceProviderRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        existing.setName(updated.getName());
        existing.setContact(updated.getContact());
        existing.setActive(updated.isActive());
        return ResponseEntity.ok(insuranceProviderRepository.save(existing));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can manage insurance providers."));
        }
        if (!insuranceProviderRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        insuranceProviderRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
