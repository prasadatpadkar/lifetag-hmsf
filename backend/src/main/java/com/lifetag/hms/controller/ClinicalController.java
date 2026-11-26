package com.lifetag.hms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifetag.hms.dto.ApiResponse;
import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.*;
import com.lifetag.hms.security.UserPrincipal;
import com.lifetag.hms.service.AdmissionBillingService;
import com.lifetag.hms.service.AdmissionService;
import com.lifetag.hms.service.NotificationService;
import com.lifetag.hms.util.Input;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * A patient's clinical history in one place: vitals (BP, sugar, SpO2...), diagnostic reports
 * (MRI, CT, X-Ray, ECG, lab tests...), treatments with medications, and inpatient admissions.
 *
 * Who can do what:
 *  - Admin          : read and write everything.
 *  - Doctor         : read and write records of patients they have an appointment with
 *                     (the same "assigned patients" rule the My Patients screen uses). The doctor
 *                     sees the patient's WHOLE history, including what other doctors did earlier.
 *  - Patient        : read only, and only their own record.
 *  - Reception      : no clinical access, but may admit / discharge patients (bed allocation).
 */
@RestController
@RequestMapping("/api/clinical")
public class ClinicalController {

    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private VitalsRepository vitalsRepository;
    @Autowired private DiagnosticReportRepository reportRepository;
    @Autowired private TreatmentRepository treatmentRepository;
    @Autowired private AdmissionRepository admissionRepository;
    @Autowired private RoomBedRepository roomBedRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private AdmissionService admissionService;
    @Autowired private AdmissionBillingService admissionBilling;
    @Autowired private ObjectMapper mapper;

    // ------------------------------------------------------------------ access rules

    private Role roleOf(UserPrincipal p) {
        return p == null ? null : p.getUser().getRole();
    }

    private Doctor myDoctor(UserPrincipal p) {
        if (roleOf(p) != Role.DOCTOR) return null;
        return doctorRepository.findByUserId(p.getUser().getId()).orElse(null);
    }

    private boolean canRead(UserPrincipal p, Long patientId) {
        Role role = roleOf(p);
        if (role == Role.ADMIN) return true;
        if (role == Role.PATIENT) {
            return patientRepository.findByUserId(p.getUser().getId())
                    .map(pt -> pt.getId().equals(patientId)).orElse(false);
        }
        if (role == Role.DOCTOR) {
            Doctor d = myDoctor(p);
            return d != null && appointmentRepository.existsByDoctorIdAndPatientId(d.getId(), patientId);
        }
        return false;
    }

    private boolean canWrite(UserPrincipal p, Long patientId) {
        Role role = roleOf(p);
        if (role == Role.ADMIN) return true;
        return role == Role.DOCTOR && canRead(p, patientId);
    }

    private ResponseEntity<?> forbidden(String msg) {
        return ResponseEntity.status(403).body(new ApiResponse(false, msg));
    }

    // ------------------------------------------------------------------ full history

    /** Everything we know about one patient, newest first. */
    @GetMapping("/patient/{patientId}/summary")
    public ResponseEntity<?> summary(@PathVariable Long patientId, @AuthenticationPrincipal UserPrincipal principal) {
        if (!canRead(principal, patientId)) {
            return forbidden(roleOf(principal) == Role.DOCTOR
                    ? "You can only open the records of patients you have an appointment with."
                    : "You do not have access to this medical record.");
        }
        Patient patient = patientRepository.findById(patientId).orElse(null);
        if (patient == null) return ResponseEntity.notFound().build();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", patient);
        out.put("vitals", vitalsRepository.findByPatientIdOrderByRecordedAtDesc(patientId));
        out.put("reports", reportRepository.findByPatientIdOrderByReportDateDescIdDesc(patientId));
        out.put("treatments", treatmentRepository.findByPatientIdOrderByStartDateDescIdDesc(patientId));
        out.put("admissions", admissionRepository.findByPatientIdOrderByAdmittedOnDescIdDesc(patientId));
        List<Appointment> appts = new ArrayList<>(appointmentRepository.findByPatientId(patientId));
        appts.sort((a, b) -> String.valueOf(b.getAppointmentDate()).compareTo(String.valueOf(a.getAppointmentDate())));
        out.put("appointments", appts);
        return ResponseEntity.ok(out);
    }

    // ------------------------------------------------------------------ vitals

    @PostMapping("/vitals")
    @Transactional
    public ResponseEntity<?> addVitals(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        Long patientId = Input.id(body, "patientId");
        if (!canWrite(principal, patientId)) return forbidden("You cannot add records for this patient.");
        Patient patient = patientRepository.findById(patientId).orElse(null);
        if (patient == null) return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));

        Vitals v = convert(body, Vitals.class);
        v.setId(null);
        v.setPatient(patient);
        if (v.getRecordedAt() == null) v.setRecordedAt(java.time.LocalDateTime.now());
        if (v.getRecordedBy() == null || v.getRecordedBy().isBlank()) v.setRecordedBy(principal.getUser().getFullName());
        if (v.getSystolic() != null && v.getDiastolic() != null && v.getDiastolic() >= v.getSystolic()) {
            throw new IllegalArgumentException("Diastolic pressure must be lower than systolic pressure.");
        }
        return ResponseEntity.ok(vitalsRepository.save(v));
    }

    @DeleteMapping("/vitals/{id}")
    @Transactional
    public ResponseEntity<?> deleteVitals(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        Vitals v = vitalsRepository.findById(id).orElse(null);
        if (v == null) return ResponseEntity.notFound().build();
        if (!canWrite(principal, v.getPatient().getId())) return forbidden("You cannot change records for this patient.");
        vitalsRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ diagnostic reports

    @PostMapping("/reports")
    @Transactional
    public ResponseEntity<?> addReport(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        Long patientId = Input.id(body, "patientId");
        if (!canWrite(principal, patientId)) return forbidden("You cannot add records for this patient.");
        Patient patient = patientRepository.findById(patientId).orElse(null);
        if (patient == null) return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));

        DiagnosticReport r = convert(body, DiagnosticReport.class);
        r.setId(null);
        r.setBilled(false);
        r.setPatient(patient);
        r.setDoctor(resolveDoctor(body, principal));
        if (r.getReportDate() == null) r.setReportDate(LocalDate.now());
        if (r.getCost() == null) r.setCost(BigDecimal.ZERO);
        DiagnosticReport saved = reportRepository.save(r);
        admissionBilling.syncActiveForPatient(patientId);      // an admitted patient's running bill picks it up

        notificationService.notifyUser(patient.getUser(), "New report available",
                saved.getReportType() + " report has been added to your medical record.", "PATIENT", "patient-records.html");
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/reports/{id}")
    @Transactional
    public ResponseEntity<?> updateReport(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        DiagnosticReport r = reportRepository.findById(id).orElse(null);
        if (r == null) return ResponseEntity.notFound().build();
        if (!canWrite(principal, r.getPatient().getId())) return forbidden("You cannot change records for this patient.");
        applyBody(r, body);
        DiagnosticReport saved = reportRepository.save(r);
        admissionBilling.syncActiveForPatient(saved.getPatient().getId());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/reports/{id}")
    @Transactional
    public ResponseEntity<?> deleteReport(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        DiagnosticReport r = reportRepository.findById(id).orElse(null);
        if (r == null) return ResponseEntity.notFound().build();
        if (!canWrite(principal, r.getPatient().getId())) return forbidden("You cannot change records for this patient.");
        Long pid = r.getPatient().getId();
        reportRepository.deleteById(id);
        admissionBilling.syncActiveForPatient(pid);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ treatments

    @PostMapping("/treatments")
    @Transactional
    public ResponseEntity<?> addTreatment(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        Long patientId = Input.id(body, "patientId");
        if (!canWrite(principal, patientId)) return forbidden("You cannot add records for this patient.");
        Patient patient = patientRepository.findById(patientId).orElse(null);
        if (patient == null) return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));

        Treatment t = convert(body, Treatment.class);
        t.setId(null);
        t.setBilled(false);
        t.setPatient(patient);
        t.setDoctor(resolveDoctor(body, principal));
        if (t.getStartDate() == null) t.setStartDate(LocalDate.now());
        if (t.getProcedureCost() == null) t.setProcedureCost(BigDecimal.ZERO);
        if (t.getMedicationCost() == null) t.setMedicationCost(BigDecimal.ZERO);
        Treatment saved = treatmentRepository.save(t);
        if (saved.getAdmissionId() == null) {          // link a treatment started during a stay to that stay
            Admission active = admissionService.activeAdmission(patientId);
            if (active != null) {
                saved.setAdmissionId(active.getId());
                saved = treatmentRepository.save(saved);
            }
        }
        admissionBilling.syncActiveForPatient(patientId);

        notificationService.notifyUser(patient.getUser(), "Treatment plan updated",
                "Your doctor added a treatment for " + saved.getDiagnosis() + " to your medical record.", "PATIENT", "patient-records.html");
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/treatments/{id}")
    @Transactional
    public ResponseEntity<?> updateTreatment(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        Treatment t = treatmentRepository.findById(id).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();
        if (!canWrite(principal, t.getPatient().getId())) return forbidden("You cannot change records for this patient.");
        applyBody(t, body);
        Treatment saved = treatmentRepository.save(t);
        admissionBilling.syncActiveForPatient(saved.getPatient().getId());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/treatments/{id}")
    @Transactional
    public ResponseEntity<?> deleteTreatment(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        Treatment t = treatmentRepository.findById(id).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();
        if (!canWrite(principal, t.getPatient().getId())) return forbidden("You cannot change records for this patient.");
        Long pid = t.getPatient().getId();
        treatmentRepository.deleteById(id);
        admissionBilling.syncActiveForPatient(pid);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ admissions

    /** Admission register for the front desk / admin; optional ?status=Admitted|Discharged. */
    @GetMapping("/admissions")
    public ResponseEntity<?> listAdmissions(@RequestParam(required = false) String status,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        Role role = roleOf(principal);
        if (role != Role.ADMIN && role != Role.RECEPTION) {
            return forbidden("Only Admin or Reception can view the admission register.");
        }
        List<Admission> all = admissionRepository.findAllByOrderByAdmittedOnDescIdDesc();
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(a -> status.equalsIgnoreCase(a.getStatus())).toList();
        }
        return ResponseEntity.ok(all);
    }

    /** Admit a patient to a bed. Body: { patientId, doctorId, roomBedId, reason, diagnosis, admittedOn }.
     *  The bed is marked Occupied and the ward + per-day rate are snapshotted onto the admission. */
    @PostMapping("/admissions")
    @Transactional
    public ResponseEntity<?> admit(@RequestBody Map<String, Object> body, @AuthenticationPrincipal UserPrincipal principal) {
        Role role = roleOf(principal);
        Long patientId = Input.id(body, "patientId");
        boolean allowed = role == Role.ADMIN || role == Role.RECEPTION || canWrite(principal, patientId);
        if (!allowed) return forbidden("You cannot admit this patient.");
        Patient patient = patientRepository.findById(patientId).orElse(null);
        if (patient == null) return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid patient id"));

        RoomBed bed = null;
        if (body.get("roomBedId") != null && !body.get("roomBedId").toString().isBlank()) {
            bed = roomBedRepository.findById(Input.id(body, "roomBedId")).orElse(null);
            if (bed == null) return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid bed id"));
        }
        LocalDate admittedOn = body.get("admittedOn") != null && !body.get("admittedOn").toString().isBlank()
                ? LocalDate.parse(body.get("admittedOn").toString()) : null;
        Admission saved = admissionService.admit(patient, resolveDoctor(body, principal), bed,
                body.get("reason") == null ? null : body.get("reason").toString(),
                body.get("diagnosis") == null ? null : body.get("diagnosis").toString(), admittedOn);
        return ResponseEntity.ok(saved);
    }

    /** Discharge: frees the bed and records the discharge date and summary.
     *  Body (all optional): { dischargedOn, dischargeSummary, diagnosis } */
    @PostMapping("/admissions/{id}/discharge")
    @Transactional
    public ResponseEntity<?> discharge(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        Admission a = admissionRepository.findById(id).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        Role role = roleOf(principal);
        boolean allowed = role == Role.ADMIN || role == Role.RECEPTION || canWrite(principal, a.getPatient().getId());
        if (!allowed) return forbidden("You cannot discharge this patient.");
        Map<String, Object> b = body == null ? new HashMap<>() : body;
        LocalDate on = b.get("dischargedOn") != null && !b.get("dischargedOn").toString().isBlank()
                ? LocalDate.parse(b.get("dischargedOn").toString()) : LocalDate.now();
        Admission saved = admissionService.discharge(a, on,
                b.get("dischargeSummary") == null ? null : b.get("dischargeSummary").toString(),
                b.get("diagnosis") == null ? null : b.get("diagnosis").toString(), true);
        return ResponseEntity.ok(saved);
    }

    // ------------------------------------------------------------------ helpers

    private Doctor resolveDoctor(Map<String, Object> body, UserPrincipal principal) {
        Doctor mine = myDoctor(principal);
        if (mine != null) return mine;
        if (body.get("doctorId") != null && !body.get("doctorId").toString().isBlank()) {
            return doctorRepository.findById(Input.id(body, "doctorId")).orElse(null);
        }
        return null;
    }

    private <T> T convert(Map<String, Object> body, Class<T> type) {
        Map<String, Object> copy = new HashMap<>(body);
        copy.remove("patient");
        copy.remove("doctor");
        copy.remove("roomBed");
        try {
            return mapper.convertValue(copy, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Some of the values are not valid - check numbers and dates.");
        }
    }

    /** Copies the supplied fields onto an existing record (ids, links and the billed flag are never overwritten). */
    private void applyBody(Object target, Map<String, Object> body) {
        Map<String, Object> copy = new HashMap<>(body);
        for (String k : new String[]{"id", "patient", "doctor", "roomBed", "billed", "patientId", "doctorId"}) copy.remove(k);
        try {
            mapper.readerForUpdating(target).readValue(mapper.writeValueAsBytes(copy));
        } catch (Exception e) {
            throw new IllegalArgumentException("Some of the values are not valid - check numbers and dates.");
        }
    }
}
