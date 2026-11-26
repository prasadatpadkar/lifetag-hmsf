package com.lifetag.hms.repository;

import com.lifetag.hms.entity.RoomBed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoomBedRepository extends JpaRepository<RoomBed, Long> {
    List<RoomBed> findByPatientId(Long patientId);
}
