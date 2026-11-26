package com.lifetag.hms.repository;

import com.lifetag.hms.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    /** Looks up the Patient profile linked to a given login account — used by
     *  GET /api/patients/me so the patient portal can reliably resolve "who am
     *  I" from the authenticated session instead of guessing from a typed name. */
    Optional<Patient> findByUserId(Long userId);
}
