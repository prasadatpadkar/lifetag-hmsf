package com.lifetag.hms.repository;

import com.lifetag.hms.entity.Treatment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TreatmentRepository extends JpaRepository<Treatment, Long> {
    List<Treatment> findByPatientIdOrderByStartDateDescIdDesc(Long patientId);
    List<Treatment> findByPatientIdAndBilledFalse(Long patientId);
}
