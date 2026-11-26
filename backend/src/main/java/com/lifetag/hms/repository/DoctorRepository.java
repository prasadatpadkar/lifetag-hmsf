package com.lifetag.hms.repository;

import com.lifetag.hms.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    /** Looks up the Doctor profile linked to a given login account — used by
     *  GET /api/doctors/me so the doctor portal can reliably resolve "who am I"
     *  from the authenticated session instead of guessing from a typed name. */
    Optional<Doctor> findByUserId(Long userId);
}
