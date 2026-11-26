package com.lifetag.hms.repository;

import com.lifetag.hms.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findByPatientId(Long patientId);
    List<Appointment> findByDoctorId(Long doctorId);
    boolean existsByDoctorIdAndPatientId(Long doctorId, Long patientId);
    List<Appointment> findByPatientIdAndBilledFalseAndStatus(Long patientId, String status);
}
