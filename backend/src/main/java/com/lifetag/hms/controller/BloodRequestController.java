package com.lifetag.hms.controller;

import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.BloodRequest;
import com.lifetag.hms.entity.Doctor;
import com.lifetag.hms.entity.Patient;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.AppointmentRepository;
import com.lifetag.hms.repository.BloodRequestRepository;
import com.lifetag.hms.repository.DoctorRepository;
import com.lifetag.hms.repository.PatientRepository;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.AdmissionBillingService;
import com.lifetag.hms.service.BloodStockService;
import com.lifetag.hms.service.NotificationService;
import com.lifetag.hms.util.Input;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Blood requests.
 *  - Admin / Reception: see, raise, fulfil, cancel and delete requests.
 *  - Doctor: raise a request (Requested / Urgent) for a patient they have an appointment with, and
 *    see the requests of those patients.
 *
 * Marking a request Fulfilled issues the units from the blood bank (stock is deducted, oldest expiry
 * first, and it fails if there is not enough), prices it and puts the charge on the patient's
 * running admission invoice - or, for an outpatient, on the next invoice as a ready-made charge.
 */
@RestController
@RequestMapping("/api/blood-requests")
public class BloodRequestController {

    private static final Set<String> STATUSES = Set.of("Requested", "Urgent", "Fulfilled", "Cancelled");

    @Autowired private BloodRequestRepository bloodRequestRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private BloodStockService bloodStock;
    @Autowired private AdmissionBillingService admissionBilling;
    @Autowired private NotificationService notificationService;

    private Role roleOf(UserPrincipal p) {
        return p == null ? null : p.getUser().getRole();
    }

    private boolean isStaff(UserPrincipal p) {
        return roleOf(p) == Role.ADMIN || roleOf(p) == Role.RECEPTION;
    }

    private Doctor myDoctor(UserPrincipal p) {
        return roleOf(p) != Role.DOCTOR ? null : doctorRepository.findByUserId(p.getUser().getId()).orElse(null);
    }

    private boolean doctorCanSee(UserPrincipal p, Long patientId) {
        Doctor d = myDoctor(p);
        return d != null && appointmentRepository.existsByDoctorIdAndPatientId(d.getId(), patientId);
    }

    private ResponseEntity<?> forbidden(String msg) {
        return ResponseEntity.status(403).body(new ApiResponse(false, msg));
    }

    @GetMapping
    public List<BloodRequest> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        if (isStaff(principal)) return bloodRequestRepository.findAll();
        if (roleOf(principal) == Role.DOCTOR) {
            return bloodRequestRepository.findAll().stream()
                    .filter(r -> r.getPatient() != null && doctorCanSee(principal, r.getPatient().getId())).toList();
        }
        return List.of();
    }

    private static String validGroup(Object v) {
        if (v == null) throw new IllegalArgumentException("Blood group is required.");
        String g = v.toString().trim().toUpperCase();
        if (!BloodStockService.GROUPS.contains(g)) throw new IllegalArgumentException("Choose a valid blood group (A+, A-, B+, B-, AB+, AB-, O+, O-).");
        return g;
    }

    private static int validUnits(Object v) {
        try {
            int u = Integer.parseInt(v.toString().trim());
            if (u < 1 || u > 20) throw new IllegalArgumentException("Units must be between 1 and 20.");
            return u;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Units must be a whole number.");
        }
    }

    private static String validStatus(Object v) {
        String s = v.toString().trim();
        if (!STATUSES.contains(s)) throw new IllegalArgumentException("Invalid status. Allowed: Requested, Urgent, Fulfilled, Cancelled.");
        return s;
    }

    /** Body: { "patientId": 1, "bloodGroup": "O+", "units": 2, "ward": "ICU", "status": "Urgent" } */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal) && roleOf(principal) != Role.DOCTOR) return forbidden("You cannot raise a blood request.");
        if (body.get("patientId") == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Patient is required."));
        }
        Patient patient = patientRepository.findById(Input.id(body, "patientId")).orElse(null);
        if (patient == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));
        }
        if (roleOf(principal) == Role.DOCTOR && !doctorCanSee(principal, patient.getId())) {
            return forbidden("You can only request blood for patients you have an appointment with.");
        }
        BloodRequest request = new BloodRequest();
        request.setPatient(patient);
        request.setBloodGroup(validGroup(body.get("bloodGroup") != null ? body.get("bloodGroup") : patient.getBloodGroup()));
        if (body.get("units") != null) request.setUnits(validUnits(body.get("units")));
        if (body.get("ward") != null) request.setWard(Input.text(body.get("ward").toString(), "Ward", 50));
        request.setRequestedBy(principal.getUser().getFullName());
        String status = body.get("status") == null ? "Requested" : validStatus(body.get("status"));
        // Fulfilling / cancelling is a blood-bank desk decision, never made at request time
        if (!"Urgent".equals(status)) status = "Requested";
        request.setStatus(status);
        BloodRequest saved = bloodRequestRepository.save(request);

        notificationService.notifyRole(Role.RECEPTION, "Blood requested" + ("Urgent".equals(status) ? " (URGENT)" : ""),
                saved.getUnits() + " unit(s) of " + saved.getBloodGroup() + " for " + patient.getName() + ".", "BLOOD", "blood-bank-management.html");
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) return forbidden("Only Admin or Reception can update a blood request.");
        BloodRequest existing = bloodRequestRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if ("Fulfilled".equals(existing.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "These units were already issued and charged - a fulfilled request can no longer be changed."));
        }
        if ("Cancelled".equals(existing.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "A cancelled request cannot be changed. Raise a new request instead."));
        }
        if (body.get("patientId") != null) {
            Patient p = patientRepository.findById(Input.id(body, "patientId")).orElse(null);
            if (p == null) return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));
            existing.setPatient(p);
        }
        if (body.get("bloodGroup") != null) existing.setBloodGroup(validGroup(body.get("bloodGroup")));
        if (body.get("units") != null) existing.setUnits(validUnits(body.get("units")));
        if (body.get("ward") != null) existing.setWard(Input.text(body.get("ward").toString(), "Ward", 50));
        String target = body.get("status") == null ? existing.getStatus() : validStatus(body.get("status"));
        existing.setStatus(target);
        boolean fulfilling = "Fulfilled".equals(target);
        if (fulfilling) bloodStock.issue(existing);         // throws (-> 400, nothing saved) if stock is short
        BloodRequest saved = bloodRequestRepository.save(existing);
        if (fulfilling) {
            if (saved.getAdmission() != null) admissionBilling.attachBlood(saved.getAdmission(), saved);
            notificationService.notifyUser(saved.getPatient().getUser(), "Blood issued",
                    saved.getUnits() + " unit(s) of " + saved.getBloodGroup() + " were issued for you. The charge of ₹"
                            + saved.getTotalCharge() + " is on your bill.", "BILLING", "patient-billing.html");
        }
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (!isStaff(principal)) return forbidden("Only Admin or Reception can remove a blood request.");
        BloodRequest r = bloodRequestRepository.findById(id).orElse(null);
        if (r == null) return ResponseEntity.notFound().build();
        if ("Fulfilled".equals(r.getStatus())) {
            return ResponseEntity.badRequest().body(new ApiResponse(false,
                    "Blood was issued for this request and charged to the patient, so the record must be kept."));
        }
        bloodRequestRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
