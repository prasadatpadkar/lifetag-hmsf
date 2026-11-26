package com.lifetag.hms.controller;

import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.MenuItem;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.MenuItemRepository;
import com.lifetag.hms.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * GET  /api/menu             - the signed-in user's own sidebar (active items for their role)
 * /api/admin/menu[/{id}]     - Admin-only management (SecurityConfig restricts /api/admin/**)
 */
@RestController
public class MenuController {

    /** Pages whose menu entry may never be deleted/disabled/moved: it would lock admins out of this screen. */
    private static boolean isProtected(MenuItem m) {
        return m.getRole() == Role.ADMIN && "menu-management.html".equalsIgnoreCase(m.getUrl());
    }

    private static final Set<String> ICONS = Set.of("dashboard", "patients", "doctors", "calendar", "bed", "blood",
            "billing", "shield", "bank", "chart", "feedback", "clock", "users", "menu", "audit", "dot");

    @Autowired
    private MenuItemRepository menuRepository;

    @GetMapping("/api/menu")
    public ResponseEntity<?> myMenu(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return ResponseEntity.status(401).body(new ApiResponse(false, "Sign in required."));
        return ResponseEntity.ok(menuRepository.findByRoleAndActiveTrueOrderBySortOrderAscIdAsc(principal.getUser().getRole()));
    }

    @GetMapping("/api/admin/menu")
    public ResponseEntity<?> all() {
        return ResponseEntity.ok(menuRepository.findAllByOrderByRoleAscSortOrderAscIdAsc());
    }

    @GetMapping("/api/admin/menu/{id}")
    public ResponseEntity<?> one(@PathVariable Long id) {
        return menuRepository.findById(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/admin/menu")
    @Transactional
    public ResponseEntity<?> create(@Valid @RequestBody MenuItem item) {
        item.setId(null); // never trust a client-supplied id on create
        String problem = check(item, null);
        if (problem != null) return ResponseEntity.badRequest().body(new ApiResponse(false, problem));
        normalize(item);
        return ResponseEntity.ok(menuRepository.save(item));
    }

    @PutMapping("/api/admin/menu/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody MenuItem in) {
        MenuItem existing = menuRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        String problem = check(in, id);
        if (problem != null) return ResponseEntity.badRequest().body(new ApiResponse(false, problem));
        if (isProtected(existing) && (!in.isActive() || in.getRole() != Role.ADMIN
                || !"menu-management.html".equalsIgnoreCase(in.getUrl()))) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "The Menu Management entry for Admin must stay active and unchanged, or admins would be locked out."));
        }
        normalize(in);
        existing.setRole(in.getRole());
        existing.setGroupLabel(in.getGroupLabel());
        existing.setLabel(in.getLabel());
        existing.setUrl(in.getUrl());
        existing.setIcon(in.getIcon());
        existing.setSortOrder(in.getSortOrder());
        existing.setActive(in.isActive());
        return ResponseEntity.ok(menuRepository.save(existing));
    }

    @DeleteMapping("/api/admin/menu/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        MenuItem existing = menuRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (isProtected(existing)) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "The Menu Management entry for Admin cannot be deleted, or admins would be locked out."));
        }
        menuRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String check(MenuItem m, Long selfId) {
        if (m.getIcon() != null && !m.getIcon().isBlank() && !ICONS.contains(m.getIcon())) return "Unknown icon.";
        boolean dup = selfId == null
                ? menuRepository.existsByRoleAndUrlIgnoreCase(m.getRole(), m.getUrl())
                : menuRepository.existsByRoleAndUrlIgnoreCaseAndIdNot(m.getRole(), m.getUrl(), selfId);
        return dup ? "That page is already in this role's menu." : null;
    }

    private static void normalize(MenuItem m) {
        m.setLabel(m.getLabel().trim());
        m.setUrl(m.getUrl().trim());
        m.setGroupLabel(m.getGroupLabel() == null ? "" : m.getGroupLabel().trim().toUpperCase());
        if (m.getIcon() == null || m.getIcon().isBlank()) m.setIcon("dot");
    }
}
