package com.lifetag.hms.repository;

import com.lifetag.hms.entity.PatientScheme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PatientSchemeRepository extends JpaRepository<PatientScheme, Long> {
    List<PatientScheme> findByPatientId(Long patientId);
}
