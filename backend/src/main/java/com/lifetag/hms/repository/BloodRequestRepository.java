package com.lifetag.hms.repository;

import com.lifetag.hms.entity.BloodRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BloodRequestRepository extends JpaRepository<BloodRequest, Long> {
    List<BloodRequest> findByPatientIdAndStatusAndBilledFalse(Long patientId, String status);
    List<BloodRequest> findByAdmissionIdAndStatus(Long admissionId, String status);
    List<BloodRequest> findByPatientIdOrderByIdDesc(Long patientId);
}
