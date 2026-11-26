package com.lifetag.hms.controller;

import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.entity.Scheme;
import com.lifetag.hms.repository.SchemeRepository;
import com.lifetag.hms.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/schemes")
public class SchemeController {

    @Autowired
    private SchemeRepository schemeRepository;

    private boolean isAdmin(UserPrincipal principal) {
        return principal != null && principal.getUser().getRole() == Role.ADMIN;
    }

    /** Anyone signed in can read the catalog (Reception/Patient need it to enroll/view). */
    @GetMapping
    public List<Scheme> getAll() {
        return schemeRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Scheme> getById(@PathVariable Long id) {
        return schemeRepository.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@Valid @RequestBody Scheme scheme, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can manage government schemes."));
        }
        return ResponseEntity.ok(schemeRepository.save(scheme));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Scheme updated, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can manage government schemes."));
        }
        return schemeRepository.findById(id).map(existing -> {
            existing.setName(updated.getName());
            existing.setDescription(updated.getDescription());
            existing.setEligibility(updated.getEligibility());
            existing.setBenefits(updated.getBenefits());
            existing.setCategory(updated.getCategory());
            existing.setStatus(updated.getStatus());
            return ResponseEntity.ok(schemeRepository.save(existing));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).body(new ApiResponse(false, "Only an administrator can manage government schemes."));
        }
        if (!schemeRepository.existsById(id)) return ResponseEntity.notFound().build();
        schemeRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
