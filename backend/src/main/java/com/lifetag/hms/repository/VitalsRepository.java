package com.lifetag.hms.repository;

import com.lifetag.hms.entity.Vitals;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VitalsRepository extends JpaRepository<Vitals, Long> {
    List<Vitals> findByPatientIdOrderByRecordedAtDesc(Long patientId);
}
