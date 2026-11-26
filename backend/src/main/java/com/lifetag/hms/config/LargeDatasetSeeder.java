package com.lifetag.hms.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.*;
import com.lifetag.hms.service.InvoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Loads the large demo dataset (resources/seed/large-dataset.json, produced by
 * backend/tools/generate_seed_dataset.py) so every module has realistic volume: ~300 patients with
 * years of vitals / BP readings, MRI-CT-ECG-lab reports, treatments and admissions, itemised
 * invoices, insurance, government schemes, blood bank, feedback and activity logs.
 *
 * All dates in the file are day offsets from today, so the data always looks current. Runs only
 * with the demo profile (app.seed.demo-data=true) and only while the hospital still has fewer than
 * 100 patients; it never touches real data in a production database.
 *
 * Every bulk login uses the shared demo password documented in CREDENTIALS.md.
 */
@Component
@Order(5)
public class LargeDatasetSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LargeDatasetSeeder.class);
    static final String DEMO_PASSWORD = "Demo@2026!";

    @Value("${app.seed.demo-data:false}")
    private boolean seedDemoData;
    @Value("${app.seed.large-dataset:true}")
    private boolean largeDataset;

    private final UserRepository userRepository;
    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final RoomBedRepository roomBedRepository;
    private final AdmissionRepository admissionRepository;
    private final AppointmentRepository appointmentRepository;
    private final VitalsRepository vitalsRepository;
    private final DiagnosticReportRepository reportRepository;
    private final TreatmentRepository treatmentRepository;
    private final BillingRepository billingRepository;
    private final InsuranceProviderRepository providerRepository;
    private final InsuranceRepository insuranceRepository;
    private final InsuranceClaimRepository insuranceClaimRepository;
    private final SchemeRepository schemeRepository;
    private final PatientSchemeRepository patientSchemeRepository;
    private final SchemeClaimRepository schemeClaimRepository;
    private final BloodBankRepository bloodBankRepository;
    private final BloodRequestRepository bloodRequestRepository;
    private final FeedbackRepository feedbackRepository;
    private final StaffActivityRepository staffActivityRepository;
    private final NotificationRepository notificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final InvoiceService invoiceService;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager txManager;

    public LargeDatasetSeeder(UserRepository userRepository, DoctorRepository doctorRepository, PatientRepository patientRepository,
                              RoomBedRepository roomBedRepository, AdmissionRepository admissionRepository,
                              AppointmentRepository appointmentRepository, VitalsRepository vitalsRepository,
                              DiagnosticReportRepository reportRepository, TreatmentRepository treatmentRepository,
                              BillingRepository billingRepository, InsuranceProviderRepository providerRepository,
                              InsuranceRepository insuranceRepository, InsuranceClaimRepository insuranceClaimRepository,
                              SchemeRepository schemeRepository, PatientSchemeRepository patientSchemeRepository,
                              SchemeClaimRepository schemeClaimRepository, BloodBankRepository bloodBankRepository,
                              BloodRequestRepository bloodRequestRepository, FeedbackRepository feedbackRepository,
                              StaffActivityRepository staffActivityRepository, NotificationRepository notificationRepository,
                              PasswordEncoder passwordEncoder, InvoiceService invoiceService, ObjectMapper mapper,
                              PlatformTransactionManager txManager) {
        this.userRepository = userRepository;
        this.doctorRepository = doctorRepository;
        this.patientRepository = patientRepository;
        this.roomBedRepository = roomBedRepository;
        this.admissionRepository = admissionRepository;
        this.appointmentRepository = appointmentRepository;
        this.vitalsRepository = vitalsRepository;
        this.reportRepository = reportRepository;
        this.treatmentRepository = treatmentRepository;
        this.billingRepository = billingRepository;
        this.providerRepository = providerRepository;
        this.insuranceRepository = insuranceRepository;
        this.insuranceClaimRepository = insuranceClaimRepository;
        this.schemeRepository = schemeRepository;
        this.patientSchemeRepository = patientSchemeRepository;
        this.schemeClaimRepository = schemeClaimRepository;
        this.bloodBankRepository = bloodBankRepository;
        this.bloodRequestRepository = bloodRequestRepository;
        this.feedbackRepository = feedbackRepository;
        this.staffActivityRepository = staffActivityRepository;
        this.notificationRepository = notificationRepository;
        this.passwordEncoder = passwordEncoder;
        this.invoiceService = invoiceService;
        this.mapper = mapper;
        this.txManager = txManager;
    }

    // ------------------------------------------------------------------ helpers

    private final LocalDate today = LocalDate.now();

    private LocalDate day(int off) {
        return today.plusDays(off);
    }

    private LocalDate day(JsonNode n, String key) {
        return n.hasNonNull(key) ? day(n.get(key).asInt()) : null;
    }

    private static String text(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asText() : null;
    }

    private static Integer integer(JsonNode n, String key) {
        return n.hasNonNull(key) ? Integer.valueOf(n.get(key).asInt()) : null;
    }

    private static Double dbl(JsonNode n, String key) {
        return n.hasNonNull(key) ? Double.valueOf(n.get(key).asDouble()) : null;
    }

    private static BigDecimal dec(JsonNode n, String key) {
        return n.hasNonNull(key) ? new BigDecimal(n.get(key).asText()) : BigDecimal.ZERO;
    }

    private String instant(int off, int hour) {
        return day(off).atTime(hour, 30).atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // ------------------------------------------------------------------ run

    @Override
    public void run(String... args) {
        if (!seedDemoData || !largeDataset) return;
        if (patientRepository.count() >= 100) return;
        ClassPathResource res = new ClassPathResource("seed/large-dataset.json");
        if (!res.exists()) {
            log.warn("Large dataset file seed/large-dataset.json not found - skipping.");
            return;
        }
        long started = System.currentTimeMillis();
        AuditEventListener.suspended = true;
        try (InputStream in = res.getInputStream()) {
            JsonNode root = mapper.readTree(in);
            new TransactionTemplate(txManager).executeWithoutResult(status -> load(root));
            log.info("Large demo dataset loaded in {} ms. Bulk logins use the password in CREDENTIALS.md.",
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("Loading the large demo dataset failed - the transaction was rolled back and the app continues "
                    + "with the small starter data.", e);
        } finally {
            AuditEventListener.suspended = false;
        }
    }

    private void load(JsonNode root) {
        String hash = passwordEncoder.encode(DEMO_PASSWORD);

        // ---------------- staff & doctors
        for (JsonNode s : root.get("staff")) {
            String username = text(s, "username");
            if (userRepository.existsByUsername(username)) continue;
            userRepository.save(new User(username, hash, username + "@lifetag.com", text(s, "name"), Role.valueOf(text(s, "role"))));
        }
        List<Doctor> existingDoctors = doctorRepository.findAll();
        List<Doctor> doctors = new ArrayList<>();
        for (JsonNode n : root.get("doctors")) {
            Doctor d = null;
            for (Doctor e : existingDoctors) {
                if (e.getEmail() != null && e.getEmail().equalsIgnoreCase(text(n, "email"))) d = e;
            }
            if (d == null) {
                String username = text(n, "username");
                if (userRepository.existsByUsername(username)) username = username + "2";
                User u = userRepository.save(new User(username, hash, text(n, "email"), text(n, "name"), Role.DOCTOR));
                d = new Doctor();
                d.setName(text(n, "name"));
                d.setSpecialization(text(n, "specialization"));
                d.setDepartment(text(n, "department"));
                d.setContact(text(n, "contact"));
                d.setEmail(text(n, "email"));
                d.setQualification(text(n, "qualification"));
                d.setExperienceYears(integer(n, "experience"));
                d.setAvailableDays(text(n, "days"));
                d.setStatus(text(n, "status"));
                d.setUser(u);
            }
            if (d.getConsultationFee() == null) d.setConsultationFee(new BigDecimal(n.get("fee").asText()));
            doctors.add(doctorRepository.save(d));
        }

        // ---------------- patients
        List<Patient> existingPatients = patientRepository.findAll();
        List<Patient> patients = new ArrayList<>();
        for (JsonNode n : root.get("patients")) {
            Patient p = null;
            if (n.hasNonNull("existingEmail")) {
                for (Patient e : existingPatients) {
                    if (e.getEmail() != null && e.getEmail().equalsIgnoreCase(text(n, "existingEmail"))) p = e;
                }
            }
            if (p == null) {
                String username = text(n, "username");
                if (username == null) username = text(n, "email").split("@")[0];
                if (userRepository.existsByUsername(username)) username = username + "x";
                User u = userRepository.save(new User(username, hash, text(n, "email"), text(n, "name"), Role.PATIENT));
                p = new Patient();
                p.setName(text(n, "name"));
                p.setAge(integer(n, "age"));
                p.setGender(text(n, "gender"));
                p.setContact(text(n, "contact"));
                p.setEmail(text(n, "email"));
                p.setAddress(text(n, "address"));
                p.setBloodGroup(text(n, "bloodGroup"));
                p.setEmergencyContact(text(n, "emergencyContact"));
                p.setStatus(text(n, "status"));
                p.setRegistrationDate(day(n, "regOffset"));
                p.setUser(u);
            }
            p.setAllergies(text(n, "allergies"));
            p.setChronicConditions(text(n, "chronicConditions"));
            patients.add(patientRepository.save(p));
        }

        // ---------------- beds & admissions
        List<RoomBed> existingBeds = roomBedRepository.findAll();
        List<RoomBed> beds = new ArrayList<>();
        for (JsonNode n : root.get("beds")) {
            RoomBed b = null;
            for (RoomBed e : existingBeds) {
                if (e.getRoomNumber().equalsIgnoreCase(text(n, "roomNumber"))) b = e;
            }
            if (b == null) {
                b = new RoomBed();
                b.setRoomNumber(text(n, "roomNumber"));
                b.setBedNumber(text(n, "bedNumber"));
                b.setRoomType(text(n, "roomType"));
                b.setChargePerDay(dbl(n, "chargePerDay"));
                b.setStatus(text(n, "status"));
                b = roomBedRepository.save(b);
            }
            beds.add(b);
        }
        List<Admission> admissions = new ArrayList<>();
        for (JsonNode n : root.get("admissions")) {
            Admission a = new Admission();
            Patient p = patients.get(n.get("p").asInt());
            RoomBed bed = beds.get(n.get("bed").asInt());
            a.setPatient(p);
            a.setDoctor(doctors.get(n.get("d").asInt()));
            a.setRoomBed(bed);
            a.setWardType(text(n, "ward"));
            a.setRoomNumber(text(n, "room"));
            a.setBedNumber(text(n, "bedNo"));
            a.setChargePerDay(dec(n, "rate"));
            a.setAdmittedOn(day(n, "admit"));
            a.setDischargedOn(day(n, "discharge"));
            a.setReason(text(n, "reason"));
            a.setDiagnosis(text(n, "diagnosis"));
            a.setStatus(text(n, "status"));
            a.setDischargeSummary(text(n, "summary"));
            a.setBilled(n.get("billed").asBoolean());
            admissions.add(admissionRepository.save(a));
            if ("Admitted".equals(a.getStatus())) {
                bed.setStatus("Occupied");
                bed.setPatient(p);
                roomBedRepository.save(bed);
            }
        }

        // ---------------- appointments, vitals, reports, treatments
        List<Appointment> appointments = new ArrayList<>();
        for (JsonNode n : root.get("appointments")) {
            Appointment a = new Appointment();
            a.setPatient(patients.get(n.get("p").asInt()));
            a.setDoctor(doctors.get(n.get("d").asInt()));
            a.setAppointmentDate(day(n, "off"));
            a.setAppointmentTime(LocalTime.parse(text(n, "time")));
            a.setReason(text(n, "reason"));
            a.setStatus(text(n, "status"));
            a.setNotes(text(n, "notes"));
            a.setBilled(n.get("billed").asBoolean());
            appointments.add(appointmentRepository.save(a));
        }
        for (JsonNode n : root.get("vitals")) {
            Vitals v = new Vitals();
            v.setPatient(patients.get(n.get("p").asInt()));
            v.setRecordedAt(day(n.get("off").asInt()).atTime(LocalTime.parse(text(n, "time"))));
            v.setRecordedBy(text(n, "by"));
            v.setSystolic(integer(n, "systolic"));
            v.setDiastolic(integer(n, "diastolic"));
            v.setPulse(integer(n, "pulse"));
            v.setTemperatureC(dbl(n, "temperatureC"));
            v.setSpo2(integer(n, "spo2"));
            v.setRespiratoryRate(integer(n, "respiratoryRate"));
            v.setWeightKg(dbl(n, "weightKg"));
            v.setHeightCm(dbl(n, "heightCm"));
            v.setBloodSugar(integer(n, "bloodSugar"));
            v.setSugarType(text(n, "sugarType"));
            vitalsRepository.save(v);
        }
        List<DiagnosticReport> reports = new ArrayList<>();
        for (JsonNode n : root.get("reports")) {
            DiagnosticReport r = new DiagnosticReport();
            r.setPatient(patients.get(n.get("p").asInt()));
            r.setDoctor(doctors.get(n.get("d").asInt()));
            r.setCategory(text(n, "category"));
            r.setReportType(text(n, "type"));
            r.setTitle(text(n, "title"));
            r.setReportDate(day(n, "off"));
            r.setFindings(text(n, "findings"));
            r.setImpression(text(n, "impression"));
            r.setResultStatus(text(n, "resultStatus"));
            r.setStatus("Completed");
            r.setPerformedBy(text(n, "performedBy"));
            r.setLabName(text(n, "labName"));
            r.setResultsJson(n.hasNonNull("results") ? n.get("results").toString() : null);
            r.setCost(dec(n, "cost"));
            r.setBilled(n.get("billed").asBoolean());
            reports.add(reportRepository.save(r));
        }
        List<Treatment> treatments = new ArrayList<>();
        for (JsonNode n : root.get("treatments")) {
            Treatment t = new Treatment();
            t.setPatient(patients.get(n.get("p").asInt()));
            t.setDoctor(doctors.get(n.get("d").asInt()));
            if (n.hasNonNull("adm")) t.setAdmissionId(admissions.get(n.get("adm").asInt()).getId());
            t.setDiagnosis(text(n, "diagnosis"));
            t.setDiagnosisCode(text(n, "code"));
            t.setTreatmentType(text(n, "type"));
            t.setProcedureName(text(n, "procedure"));
            t.setPlan(text(n, "plan"));
            t.setMedicationsJson(n.hasNonNull("meds") ? n.get("meds").toString() : null);
            t.setStartDate(day(n, "start"));
            t.setEndDate(day(n, "end"));
            t.setStatus(text(n, "status"));
            t.setOutcome(text(n, "outcome"));
            t.setFollowUpDate(day(n, "followUp"));
            t.setNotes(text(n, "notes"));
            t.setProcedureCost(dec(n, "procCost"));
            t.setMedicationCost(dec(n, "medCost"));
            t.setBilled(n.get("billed").asBoolean());
            treatments.add(treatmentRepository.save(t));
        }

        // ---------------- billing
        List<Billing> bills = new ArrayList<>();
        for (JsonNode n : root.get("bills")) {
            Billing b = new Billing();
            b.setPatient(patients.get(n.get("p").asInt()));
            b.setDoctor(doctors.get(n.get("d").asInt()));
            if (n.hasNonNull("adm")) b.setAdmission(admissions.get(n.get("adm").asInt()));
            b.setBillDate(day(n, "off"));
            b.setDueDate(day(n, "dueOff"));
            b.setItemsJson(n.get("items").toString());
            b.setDiscount(dec(n, "discount"));
            // Cover is NOT copied from the file: it is derived from SANCTIONED claims only (see
            // CoverageService / BillingDataMigrator), so a claim that is merely submitted or under
            // review is shown as "awaiting sanction" and never deducted from what the patient owes.
            b.setAmountPaid(dec(n, "paid"));
            b.setStatus(text(n, "status"));
            b.setPaymentMethod(text(n, "method"));
            b.setNotes(text(n, "notes"));
            ArrayNode pays = mapper.createArrayNode();
            for (JsonNode pn : n.get("payments")) {
                ObjectNode o = mapper.createObjectNode();
                o.put("date", day(pn.get("off").asInt()).toString());
                o.put("amount", new BigDecimal(pn.get("amount").asText()));
                o.put("method", text(pn, "method"));
                o.put("ref", text(pn, "ref") == null ? "" : text(pn, "ref"));
                pays.add(o);
            }
            b.setPaymentsJson(pays.size() == 0 ? null : pays.toString());
            invoiceService.recalculate(b, true);
            bills.add(billingRepository.save(b));
        }

        // ---------------- insurance & schemes
        Map<String, InsuranceProvider> providers = new HashMap<>();
        for (InsuranceProvider ip : providerRepository.findAll()) providers.put(ip.getName(), ip);
        for (JsonNode n : root.get("insuranceProviders")) {
            if (providers.containsKey(text(n, "name"))) continue;
            InsuranceProvider ip = new InsuranceProvider();
            ip.setName(text(n, "name"));
            ip.setContact(text(n, "contact"));
            providers.put(ip.getName(), providerRepository.save(ip));
        }
        List<Insurance> policies = new ArrayList<>();
        for (JsonNode n : root.get("policies")) {
            Insurance i = new Insurance();
            i.setPatient(patients.get(n.get("p").asInt()));
            i.setProvider(text(n, "provider"));
            i.setPolicyNumber(text(n, "number"));
            i.setCoverageAmount(dec(n, "coverage"));
            i.setCoverageType(text(n, "ctype"));
            i.setValidFrom(day(n, "vfrom"));
            i.setValidTo(day(n, "vto"));
            i.setStatus(text(n, "status"));
            policies.add(insuranceRepository.save(i));
        }
        for (JsonNode n : root.get("insuranceClaims")) {
            Billing bill = bills.get(n.get("bill").asInt());
            InsuranceClaim c = new InsuranceClaim();
            c.setPatient(patients.get(n.get("p").asInt()));
            c.setInsurance(policies.get(n.get("policy").asInt()));
            c.setInvoiceRef(InvoiceService.invoiceNo(bill));
            c.setClaimAmount(dec(n, "amount"));
            String st = text(n, "status");
            c.setStatus(st);
            c.setApprovedAmount("Approved".equals(st) ? dec(n, "amount") : BigDecimal.ZERO);
            c.setSubmittedDate(day(n, "sub"));
            c.setResponseDate(day(n, "resp"));
            c.setRemarks("Approved".equals(st) ? "Cashless claim approved by the insurer."
                    : "Rejected".equals(st) ? "Claim rejected - patient settled the bill directly."
                    : "Pre-authorisation submitted with discharge summary and itemised bill.");
            insuranceClaimRepository.save(c);
        }

        List<Scheme> allSchemes = schemeRepository.findAll();
        List<Scheme> schemes = new ArrayList<>();
        schemes.add(findOrCreateScheme(allSchemes, "Ayushman Bharat", "National health protection scheme offering coverage up to 5 lakh per family per year.",
                "Below poverty line families", "Cashless treatment up to Rs. 5,00,000", "Government"));
        schemes.add(findOrCreateScheme(allSchemes, "Senior Citizen Health Scheme", "Special discounted healthcare package for senior citizens.",
                "Age 60 and above", "20% discount on consultations and diagnostics", "Hospital"));
        for (JsonNode n : root.get("schemes")) {
            schemes.add(findOrCreateScheme(allSchemes, text(n, "name"), text(n, "description"), text(n, "eligibility"),
                    text(n, "benefits"), text(n, "category")));
        }
        List<PatientScheme> enrollments = new ArrayList<>();
        for (JsonNode n : root.get("enrollments")) {
            PatientScheme e = new PatientScheme();
            e.setPatient(patients.get(n.get("p").asInt()));
            e.setScheme(schemes.get(n.get("scheme").asInt()));
            e.setEnrollmentDate(day(n, "off"));
            e.setStatus(text(n, "status"));
            e.setAadhaarNumber(text(n, "aadhaar"));
            e.setBeneficiaryCardId(text(n, "card"));
            e.setVerifiedBy(text(n, "by"));
            e.setVerifiedDate(day(n, "vOff"));
            enrollments.add(patientSchemeRepository.save(e));
        }
        for (JsonNode n : root.get("schemeClaims")) {
            Billing bill = bills.get(n.get("bill").asInt());
            PatientScheme en = enrollments.get(n.get("enroll").asInt());
            String st = text(n, "status");
            SchemeClaim c = new SchemeClaim();
            c.setPatient(en.getPatient());
            c.setScheme(en.getScheme());
            c.setEnrollment(en);
            c.setInvoiceRef(InvoiceService.invoiceNo(bill));
            c.setPackageCode(n.get("pkg").get(0).asText());
            c.setPackageName(n.get("pkg").get(1).asText());
            c.setClaimedAmount(dec(n, "amount"));
            c.setSanctionedAmount("Approved".equals(st) ? dec(n, "amount") : BigDecimal.ZERO);
            c.setStatus(st);
            c.setSubmissionMode("SIMULATED");
            c.setGovtReferenceNumber("SBX-" + today.getYear() + "-" + String.format("%06d", 100000 + (n.get("bill").asInt() * 7919) % 900000));
            c.setSubmittedDate(day(n, "sub"));
            c.setResponseDate(day(n, "resp"));
            c.setRemarks("Rejected".equals(st) ? "Package documents incomplete - claim returned." : null);
            ArrayNode trail = mapper.createArrayNode();
            trail.add(trailEntry(instant(n.get("sub").asInt() - 1, 10), "Claim drafted", "Neha Kapoor"));
            trail.add(trailEntry(instant(n.get("sub").asInt(), 11), "Submitted to portal (sandbox)", "Neha Kapoor"));
            if (n.hasNonNull("resp")) {
                trail.add(trailEntry(instant(n.get("resp").asInt(), 15), "Approved".equals(st) ? "Claim approved" : "Claim rejected", "Govt reviewer"));
            }
            c.setAuditTrailJson(trail.toString());
            schemeClaimRepository.save(c);
        }

        // ---------------- blood bank, feedback, activity, notifications
        for (JsonNode n : root.get("bloodBank")) {
            BloodBank b = new BloodBank();
            b.setBloodGroup(text(n, "group"));
            b.setUnitsAvailable(integer(n, "units"));
            b.setDonorName(text(n, "donor"));
            b.setDonorContact(text(n, "contact"));
            b.setLastDonationDate(day(n, "last"));
            b.setExpiryDate(day(n, "expiry"));
            b.setStatus(text(n, "status"));
            bloodBankRepository.save(b);
        }
        for (JsonNode n : root.get("bloodRequests")) {
            BloodRequest r = new BloodRequest();
            r.setPatient(patients.get(n.get("p").asInt()));
            r.setBloodGroup(text(n, "group"));
            r.setUnits(integer(n, "units"));
            r.setWard(text(n, "ward"));
            r.setStatus(text(n, "status"));
            r.setRequestDate(day(n, "off"));
            bloodRequestRepository.save(r);
        }
        for (JsonNode n : root.get("feedback")) {
            Feedback f = new Feedback();
            f.setPatient(patients.get(n.get("p").asInt()));
            f.setDoctor(doctors.get(n.get("d").asInt()));
            f.setRating(integer(n, "rating"));
            f.setComments(text(n, "comments"));
            f.setFeedbackDate(day(n, "off"));
            feedbackRepository.save(f);
        }
        for (JsonNode n : root.get("staffActivity")) {
            StaffActivity s = new StaffActivity();
            s.setStaffName(text(n, "staff"));
            s.setRole(capitalise(text(n, "role")));
            s.setAction(text(n, "action"));
            s.setDetails(text(n, "details"));
            s.setTimestamp(LocalDateTime.of(day(n.get("off").asInt()), LocalTime.parse(text(n, "time"))));
            staffActivityRepository.save(s);
        }
        for (JsonNode n : root.get("notifications")) {
            User u = patients.get(n.get("p").asInt()).getUser();
            if (u == null) continue;
            Notification nt = new Notification(u, text(n, "title"), text(n, "message"), text(n, "type"), text(n, "link"));
            nt.setRead(n.get("read").asBoolean());
            nt.setCreatedAt(day(n.get("off").asInt()).atTime(9, 15));
            notificationRepository.save(nt);
        }
        log.info("Seeded: {} doctors, {} patients, {} beds, {} admissions, {} appointments, {} reports, {} treatments, {} invoices.",
                doctors.size(), patients.size(), beds.size(), admissions.size(), appointments.size(), reports.size(),
                treatments.size(), bills.size());
    }

    private ObjectNode trailEntry(String at, String event, String by) {
        ObjectNode o = mapper.createObjectNode();
        o.put("at", at);
        o.put("event", event);
        o.put("by", by);
        return o;
    }

    private Scheme findOrCreateScheme(List<Scheme> existing, String name, String description, String eligibility,
                                      String benefits, String category) {
        for (Scheme s : existing) {
            if (s.getName() != null && s.getName().equalsIgnoreCase(name)) return s;
        }
        Scheme s = new Scheme();
        s.setName(name);
        String lower = name.toLowerCase();
        if (lower.contains("ayushman") || lower.contains("jan arogya") || lower.contains("pmjay")) {
            s.setMaxCoverAmount(new BigDecimal("500000"));     // fixed yearly ceiling per family
        }
        s.setDescription(description);
        s.setEligibility(eligibility);
        s.setBenefits(benefits);
        s.setCategory(category);
        Scheme saved = schemeRepository.save(s);
        existing.add(saved);
        return saved;
    }
}
