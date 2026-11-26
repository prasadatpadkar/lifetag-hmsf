package com.lifetag.hms.repository;

import com.lifetag.hms.entity.Admission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdmissionRepository extends JpaRepository<Admission, Long> {
    List<Admission> findByPatientIdOrderByAdmittedOnDescIdDesc(Long patientId);
    List<Admission> findByPatientIdAndBilledFalse(Long patientId);
    List<Admission> findAllByOrderByAdmittedOnDescIdDesc();
    List<Admission> findByPatientIdAndStatus(Long patientId, String status);
    List<Admission> findByRoomBedIdAndStatus(Long roomBedId, String status);
}
