package com.lifetag.hms.repository;

import com.lifetag.hms.entity.InsuranceClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InsuranceClaimRepository extends JpaRepository<InsuranceClaim, Long> {
    List<InsuranceClaim> findByPatientId(Long patientId);
    List<InsuranceClaim> findByInvoiceRefIgnoreCase(String invoiceRef);
    List<InsuranceClaim> findByInsuranceId(Long insuranceId);
}
