package com.lifetag.hms.repository;

import com.lifetag.hms.entity.DiagnosticReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiagnosticReportRepository extends JpaRepository<DiagnosticReport, Long> {
    List<DiagnosticReport> findByPatientIdOrderByReportDateDescIdDesc(Long patientId);
    List<DiagnosticReport> findByPatientIdAndBilledFalse(Long patientId);
}
