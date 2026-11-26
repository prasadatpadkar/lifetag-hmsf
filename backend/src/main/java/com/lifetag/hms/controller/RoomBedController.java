package com.lifetag.hms.controller;

import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.Admission;
import com.lifetag.hms.entity.Patient;
import com.lifetag.hms.entity.RoomBed;
import com.lifetag.hms.repository.AdmissionRepository;
import com.lifetag.hms.repository.PatientRepository;
import com.lifetag.hms.repository.RoomBedRepository;
import com.lifetag.hms.service.AdmissionService;
import com.lifetag.hms.util.Input;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rooms & beds. Allotting a bed to a patient admits them (so the room charge is billed); vacating a
 * bed discharges them. The bed's own status can never be set to Occupied/Available behind the
 * admission's back - all of that goes through {@link AdmissionService}.
 */
@RestController
@RequestMapping("/api/rooms-beds")
public class RoomBedController {

    private static final Set<String> STATUSES = Set.of("Available", "Occupied", "Maintenance", "Cleaning");

    @Autowired private RoomBedRepository roomBedRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private AdmissionRepository admissionRepository;
    @Autowired private AdmissionService admissionService;

    @GetMapping
    public List<RoomBed> getAll() {
        return roomBedRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomBed> getById(@PathVariable Long id) {
        return roomBedRepository.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public RoomBed create(@Valid @RequestBody RoomBed roomBed) {
        roomBed.setId(null);
        roomBed.setPatient(null);                       // a new bed is empty; allot it through /assign
        roomBed.setStatus(roomBed.getStatus() != null && STATUSES.contains(roomBed.getStatus())
                && !"Occupied".equals(roomBed.getStatus()) ? roomBed.getStatus() : "Available");
        return roomBedRepository.save(roomBed);
    }

    /** Edit a bed's details / tariff, or take it out of service. A tariff change only affects
     *  future admissions - stays already in progress keep the rate they were allotted at. */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody RoomBed updated) {
        RoomBed existing = roomBedRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        String target = updated.getStatus() == null ? existing.getStatus() : updated.getStatus();
        if (!STATUSES.contains(target)) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid bed status. Allowed: Available, Occupied, Maintenance, Cleaning."));
        }
        boolean wasOccupied = "Occupied".equalsIgnoreCase(existing.getStatus());
        if ("Occupied".equals(target) && !wasOccupied) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Allot the bed to a patient to occupy it."));
        }
        if (wasOccupied && !"Occupied".equals(target)) {
            // taking an occupied bed out of use discharges the patient so billing stops
            for (Admission a : admissionRepository.findByRoomBedIdAndStatus(existing.getId(), AdmissionService.ADMITTED)) {
                admissionService.discharge(a, null, "Bed released by the ward desk.", null, true);
            }
            existing.setPatient(null);
        }
        if (updated.getRoomNumber() != null && !updated.getRoomNumber().isBlank()) existing.setRoomNumber(updated.getRoomNumber());
        existing.setBedNumber(updated.getBedNumber());
        existing.setRoomType(updated.getRoomType());
        existing.setChargePerDay(updated.getChargePerDay());
        existing.setStatus(target);
        return ResponseEntity.ok(roomBedRepository.save(existing));
    }

    /** Allot or vacate a bed. Body: { "patientId": 5 }  or  { "patientId": null }
     *  Allotting admits the patient (or transfers them if they already hold another bed) and opens /
     *  updates their inpatient invoice; vacating discharges them and finalises it. */
    @PatchMapping("/{id}/assign")
    @Transactional
    public ResponseEntity<?> assign(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        RoomBed bed = roomBedRepository.findById(id).orElse(null);
        if (bed == null) return ResponseEntity.notFound().build();
        Object patientId = body.get("patientId");
        if (patientId == null || patientId.toString().isBlank()) {
            List<Admission> stays = admissionRepository.findByRoomBedIdAndStatus(bed.getId(), AdmissionService.ADMITTED);
            for (Admission a : stays) admissionService.discharge(a, null, null, null, true);
            if (stays.isEmpty()) {                       // a bed marked occupied with no stay behind it: just free it
                bed.setPatient(null);
                if ("Occupied".equalsIgnoreCase(bed.getStatus())) bed.setStatus("Available");
                roomBedRepository.save(bed);
            }
            return ResponseEntity.ok(roomBedRepository.findById(id).orElse(bed));
        }
        Patient patient = patientRepository.findById(Input.id(body, "patientId")).orElse(null);
        if (patient == null) return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));
        if ("Occupied".equalsIgnoreCase(bed.getStatus())) {
            if (bed.getPatient() != null && bed.getPatient().getId().equals(patient.getId())) return ResponseEntity.ok(bed);
            return ResponseEntity.badRequest().body(new ApiResponse(false, "That bed is already occupied."));
        }
        Admission current = admissionService.activeAdmission(patient.getId());
        if (current != null) admissionService.transfer(current, bed);
        else admissionService.admit(patient, null, bed, null, null, null);
        return ResponseEntity.ok(roomBedRepository.findById(id).orElse(bed));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        RoomBed bed = roomBedRepository.findById(id).orElse(null);
        if (bed == null) return ResponseEntity.notFound().build();
        if ("Occupied".equalsIgnoreCase(bed.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "This bed is occupied - discharge the patient before removing it."));
        }
        roomBedRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
