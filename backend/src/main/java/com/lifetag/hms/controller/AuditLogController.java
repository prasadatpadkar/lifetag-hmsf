package com.lifetag.hms.controller;

import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.AuditLog;
import com.lifetag.hms.repository.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Read-only audit trail. Admin only (enforced in SecurityConfig for /api/audit-logs/**). */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private static final Set<String> OPERATIONS = Set.of("INSERT", "UPDATE", "DELETE", "LOGIN", "LOGOUT", "LOGIN_FAIL");

    @Autowired
    private AuditLogRepository auditLogRepository;

    /** Filter by user (who did it), table and record id (what changed) and/or operation. */
    @GetMapping
    public ResponseEntity<?> search(@RequestParam(required = false) Long userId,
                                    @RequestParam(required = false) String table,
                                    @RequestParam(required = false) Long recordId,
                                    @RequestParam(required = false) String operation,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        if (operation != null && !operation.isBlank() && !OPERATIONS.contains(operation.toUpperCase())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "operation must be INSERT, UPDATE, DELETE, LOGIN, LOGOUT or LOGIN_FAIL."));
        }
        if (table != null && !table.matches("[A-Za-z0-9_]{0,64}")) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid table name."));
        }
        Specification<AuditLog> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (userId != null) p.add(cb.equal(root.get("userId"), userId));
            if (recordId != null) p.add(cb.equal(root.get("recordId"), recordId));
            if (table != null && !table.isBlank()) p.add(cb.equal(root.get("tableName"), table));
            if (operation != null && !operation.isBlank()) p.add(cb.equal(root.get("operation"), operation.toUpperCase()));
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<AuditLog> result = auditLogRepository.findAll(spec,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.DESC, "id")));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return auditLogRepository.findById(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
