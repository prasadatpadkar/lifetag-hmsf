package com.lifetag.hms.controller;

import com.lifetag.hms.util.Input;
import com.lifetag.hms.entity.Doctor;
import com.lifetag.hms.entity.Feedback;
import com.lifetag.hms.entity.Patient;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.DoctorRepository;
import com.lifetag.hms.repository.FeedbackRepository;
import com.lifetag.hms.repository.PatientRepository;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    @Autowired
    private FeedbackRepository feedbackRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private NotificationService notificationService;

    /** Admin/Reception see everything (for the Feedback module). A Patient only
     *  sees feedback they wrote themselves; a Doctor only sees feedback left
     *  for them — so a full list fetch never hands one patient's comments to
     *  another patient, or a doctor's reviews to a different doctor. */
    @GetMapping
    public List<Feedback> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return List.of();
        Role role = principal.getUser().getRole();
        if (role == Role.ADMIN || role == Role.RECEPTION) {
            return feedbackRepository.findAll();
        }
        if (role == Role.PATIENT) {
            return patientRepository.findByUserId(principal.getUser().getId())
                    .map(p -> feedbackRepository.findAll().stream()
                            .filter(f -> f.getPatient() != null && f.getPatient().getId().equals(p.getId()))
                            .toList())
                    .orElse(List.of());
        }
        if (role == Role.DOCTOR) {
            return doctorRepository.findByUserId(principal.getUser().getId())
                    .map(d -> feedbackRepository.findAll().stream()
                            .filter(f -> f.getDoctor() != null && f.getDoctor().getId().equals(d.getId()))
                            .toList())
                    .orElse(List.of());
        }
        return List.of();
    }

    /** Body: { "patientId": 1, "doctorId": 2, "rating": 5, "comments": "Great care" }
     *  A Patient submitting their own feedback (via the patient portal) always gets
     *  patientId forced to their own record — a request body claiming a different
     *  patientId is ignored rather than trusted, so one patient can't leave feedback
     *  under another patient's name. Admin/Reception (logging feedback phoned in,
     *  say) can specify any patientId. */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        Long patientId;
        if (principal != null && principal.getUser().getRole() == Role.PATIENT) {
            Patient own = patientRepository.findByUserId(principal.getUser().getId()).orElse(null);
            if (own == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "No patient profile is linked to this account yet."));
            }
            patientId = own.getId();
        } else if (principal != null && (principal.getUser().getRole() == Role.ADMIN || principal.getUser().getRole() == Role.RECEPTION)) {
            if (body.get("patientId") == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "patientId is required."));
            }
            patientId = Input.id(body, "patientId");
        } else {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Only a patient, Admin, or Reception can log feedback."));
        }

        Patient patient = patientRepository.findById(patientId).orElse(null);
        if (patient == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid patient id"));
        }
        Feedback feedback = new Feedback();
        feedback.setPatient(patient);
        Doctor doctor = null;
        if (body.get("doctorId") != null) {
            doctor = doctorRepository.findById(Input.id(body, "doctorId")).orElse(null);
            feedback.setDoctor(doctor);
        }
        if (body.get("rating") != null) feedback.setRating(Integer.valueOf(body.get("rating").toString()));
        if (body.get("comments") != null) feedback.setComments(body.get("comments").toString());
        Feedback saved = feedbackRepository.save(feedback);

        if (doctor != null) {
            notificationService.notifyUser(doctor.getUser(), "New feedback received",
                    patient.getName() + " left you " + (saved.getRating() != null ? saved.getRating() + "-star" : "") + " feedback.",
                    "FEEDBACK", "doctor-feedback.html");
        }

        return ResponseEntity.ok(saved);
    }

    /** Only Admin/Reception edit an existing feedback entry (e.g. correcting which
     *  doctor a phoned-in review was logged against) — a patient can't rewrite
     *  their own or anyone else's feedback after the fact. */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || (principal.getUser().getRole() != Role.ADMIN && principal.getUser().getRole() != Role.RECEPTION)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Only Admin or Reception can edit a feedback entry."));
        }
        Feedback existing = feedbackRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.get("patientId") != null) {
            patientRepository.findById(Input.id(body, "patientId")).ifPresent(existing::setPatient);
        }
        if (body.get("doctorId") != null) {
            doctorRepository.findById(Input.id(body, "doctorId")).ifPresent(existing::setDoctor);
        }
        if (body.get("rating") != null) existing.setRating(Integer.valueOf(body.get("rating").toString()));
        if (body.get("comments") != null) existing.setComments(body.get("comments").toString());
        return ResponseEntity.ok(feedbackRepository.save(existing));
    }

    /** Only Admin/Reception remove a feedback entry outright. */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || (principal.getUser().getRole() != Role.ADMIN && principal.getUser().getRole() != Role.RECEPTION)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Only Admin or Reception can remove a feedback entry."));
        }
        if (!feedbackRepository.existsById(id)) return ResponseEntity.notFound().build();
        feedbackRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
