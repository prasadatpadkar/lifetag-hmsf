package com.lifetag.hms.repository;

import com.lifetag.hms.entity.SchemeClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SchemeClaimRepository extends JpaRepository<SchemeClaim, Long> {
    List<SchemeClaim> findByPatientId(Long patientId);
    List<SchemeClaim> findByInvoiceRefIgnoreCase(String invoiceRef);
    List<SchemeClaim> findBySchemeIdAndPatientId(Long schemeId, Long patientId);
}
