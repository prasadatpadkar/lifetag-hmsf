package com.lifetag.hms.config;

import com.lifetag.hms.entity.*;
import com.lifetag.hms.repository.*;
import com.lifetag.hms.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Seeds demo logins plus a small set of sample records across every module, so the
 * frontend has something to show on first run instead of empty tables everywhere.
 * Runs only once (skips if users already exist).
 */
@Component
@org.springframework.core.annotation.Order(1)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** Demo logins + sample records. Disabled by default; only enable on a disposable demo database.
     *  The demo passwords are published in CREDENTIALS.md. */
    @Value("${app.seed.demo-data:false}")
    private boolean seedDemoData;

    /** Optional first-admin bootstrap for a real (non-demo) deployment. */
    @Value("${app.bootstrap-admin.username:}")
    private String bootstrapUsername;
    @Value("${app.bootstrap-admin.password:}")
    private String bootstrapPassword;
    @Value("${app.bootstrap-admin.email:}")
    private String bootstrapEmail;
    @Value("${app.bootstrap-admin.full-name:Administrator}")
    private String bootstrapFullName;

    private final UserRepository userRepository;
    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final SchemeRepository schemeRepository;
    private final RoomBedRepository roomBedRepository;
    private final BloodBankRepository bloodBankRepository;
    private final AppointmentRepository appointmentRepository;
    private final BillingRepository billingRepository;
    private final BloodRequestRepository bloodRequestRepository;
    private final FeedbackRepository feedbackRepository;
    private final InsuranceRepository insuranceRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;

    public DataSeeder(UserRepository userRepository, DoctorRepository doctorRepository,
                       PatientRepository patientRepository, SchemeRepository schemeRepository,
                       RoomBedRepository roomBedRepository, BloodBankRepository bloodBankRepository,
                       AppointmentRepository appointmentRepository, BillingRepository billingRepository,
                       BloodRequestRepository bloodRequestRepository,
                       FeedbackRepository feedbackRepository, InsuranceRepository insuranceRepository,
                       PasswordEncoder passwordEncoder, NotificationService notificationService) {
        this.userRepository = userRepository;
        this.doctorRepository = doctorRepository;
        this.patientRepository = patientRepository;
        this.schemeRepository = schemeRepository;
        this.roomBedRepository = roomBedRepository;
        this.bloodBankRepository = bloodBankRepository;
        this.appointmentRepository = appointmentRepository;
        this.billingRepository = billingRepository;
        this.bloodRequestRepository = bloodRequestRepository;
        this.feedbackRepository = feedbackRepository;
        this.insuranceRepository = insuranceRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }

        if (!seedDemoData) {
            bootstrapAdmin();
            return;
        }
        log.warn("Seeding DEMO data with publicly documented passwords (app.seed.demo-data=true). "
                + "Never enable this on a system holding real data.");

        // ---- Users: several real logins per role instead of one demo login each ----

        // Admins
        User admin1 = new User("rajesh.mehta", passwordEncoder.encode("RajeshM@2026!"), "rajesh.mehta@lifetag.com", "Rajesh Mehta", Role.ADMIN);
        User admin2 = new User("anita.deshmukh", passwordEncoder.encode("AnitaD@2026!"), "anita.deshmukh@lifetag.com", "Anita Deshmukh", Role.ADMIN);
        userRepository.save(admin1);
        userRepository.save(admin2);

        // Reception
        User reception1 = new User("neha.kapoor", passwordEncoder.encode("NehaK@2026!"), "neha.kapoor@lifetag.com", "Neha Kapoor", Role.RECEPTION);
        User reception2 = new User("suresh.pillai", passwordEncoder.encode("SureshP@2026!"), "suresh.pillai@lifetag.com", "Suresh Pillai", Role.RECEPTION);
        userRepository.save(reception1);
        userRepository.save(reception2);

        // Doctor logins (one per doctor below)
        User doctorUser1 = new User("sarah.johnson", passwordEncoder.encode("SarahJ@2026!"), "sarah.johnson@lifetag.com", "Dr. Sarah Johnson", Role.DOCTOR);
        User doctorUser2 = new User("arvind.iyer", passwordEncoder.encode("ArvindI@2026!"), "arvind.iyer@lifetag.com", "Dr. Arvind Iyer", Role.DOCTOR);
        User doctorUser3 = new User("melissa.fernandes", passwordEncoder.encode("MelissaF@2026!"), "melissa.f@lifetag.com", "Dr. Melissa Fernandes", Role.DOCTOR);
        userRepository.save(doctorUser1);
        userRepository.save(doctorUser2);
        userRepository.save(doctorUser3);

        // Patient logins (one per patient below)
        User patientUser1 = new User("john.doe", passwordEncoder.encode("JohnD@2026!"), "john.doe@lifetag.com", "John Doe", Role.PATIENT);
        User patientUser2 = new User("priya.kulkarni", passwordEncoder.encode("PriyaK@2026!"), "priya.kulkarni@lifetag.com", "Priya Kulkarni", Role.PATIENT);
        User patientUser3 = new User("arjun.nair", passwordEncoder.encode("ArjunN@2026!"), "arjun.nair@lifetag.com", "Arjun Nair", Role.PATIENT);
        userRepository.save(patientUser1);
        userRepository.save(patientUser2);
        userRepository.save(patientUser3);

        // Welcome notification for every seeded login, so the bell isn't empty on first run.
        for (User u : List.of(admin1, admin2, reception1, reception2,
                doctorUser1, doctorUser2, doctorUser3, patientUser1, patientUser2, patientUser3)) {
            notificationService.notifyUser(u, "Welcome to LifeTag HMS",
                    "Your account is set up and ready to go.", "SYSTEM", null);
        }

        // ---- Doctors ----
        Doctor doctor1 = new Doctor();
        doctor1.setName("Dr. Sarah Johnson");
        doctor1.setSpecialization("Cardiology");
        doctor1.setDepartment("Cardiology");
        doctor1.setContact("9876543210");
        doctor1.setEmail("sarah.johnson@lifetag.com");
        doctor1.setQualification("MD, DM Cardiology");
        doctor1.setExperienceYears(12);
        doctor1.setAvailableDays("Mon, Wed, Fri");
        doctor1.setUser(doctorUser1);
        doctorRepository.save(doctor1);

        Doctor doctor2 = new Doctor();
        doctor2.setName("Dr. Arvind Iyer");
        doctor2.setSpecialization("Pediatrics");
        doctor2.setDepartment("Pediatrics");
        doctor2.setContact("9812345678");
        doctor2.setEmail("arvind.iyer@lifetag.com");
        doctor2.setQualification("MD Pediatrics");
        doctor2.setExperienceYears(8);
        doctor2.setAvailableDays("Tue, Thu, Sat");
        doctor2.setUser(doctorUser2);
        doctorRepository.save(doctor2);

        Doctor doctor3 = new Doctor();
        doctor3.setName("Dr. Melissa Fernandes");
        doctor3.setSpecialization("Orthopedics");
        doctor3.setDepartment("Orthopedics");
        doctor3.setContact("9988776655");
        doctor3.setEmail("melissa.f@lifetag.com");
        doctor3.setQualification("MS Orthopedics");
        doctor3.setExperienceYears(15);
        doctor3.setAvailableDays("Mon, Tue, Fri");
        doctor3.setStatus("On Leave");
        doctor3.setUser(doctorUser3);
        doctorRepository.save(doctor3);

        // ---- Patients ----
        Patient patient1 = new Patient();
        patient1.setName("John Doe");
        patient1.setAge(34);
        patient1.setGender("Male");
        patient1.setContact("9123456780");
        patient1.setEmail("john.doe@lifetag.com");
        patient1.setAddress("Pune, Maharashtra");
        patient1.setBloodGroup("O+");
        patient1.setEmergencyContact("9000011223");
        patient1.setUser(patientUser1);
        patientRepository.save(patient1);

        Patient patient2 = new Patient();
        patient2.setName("Priya Kulkarni");
        patient2.setAge(8);
        patient2.setGender("Female");
        patient2.setContact("9812345678");
        patient2.setEmail("priya.kulkarni@lifetag.com");
        patient2.setAddress("4 Lake View, Sangli");
        patient2.setBloodGroup("A+");
        patient2.setEmergencyContact("9000011224");
        patient2.setUser(patientUser2);
        patientRepository.save(patient2);

        Patient patient3 = new Patient();
        patient3.setName("Arjun Nair");
        patient3.setAge(47);
        patient3.setGender("Male");
        patient3.setContact("9988776655");
        patient3.setEmail("arjun.nair@lifetag.com");
        patient3.setAddress("78 Station Road, Sangli");
        patient3.setBloodGroup("B-");
        patient3.setEmergencyContact("9811122334");
        patient3.setUser(patientUser3);
        patientRepository.save(patient3);

        // ---- Schemes ----
        Scheme scheme1 = new Scheme();
        scheme1.setName("Ayushman Bharat");
        scheme1.setDescription("National health protection scheme offering coverage up to 5 lakh per family per year.");
        scheme1.setEligibility("Below poverty line families");
        scheme1.setBenefits("Cashless treatment up to Rs. 5,00,000");
        scheme1.setCategory("Government");
        schemeRepository.save(scheme1);

        Scheme scheme2 = new Scheme();
        scheme2.setName("Senior Citizen Health Scheme");
        scheme2.setDescription("Special discounted healthcare package for senior citizens.");
        scheme2.setEligibility("Age 60 and above");
        scheme2.setBenefits("20% discount on consultations and diagnostics");
        scheme2.setCategory("Hospital");
        schemeRepository.save(scheme2);

        // ---- Rooms & beds (a few per ward, some occupied) ----
        seedBed("General Ward", "GEN-001", patient1, 1200.0);
        seedBed("General Ward", "GEN-002", null, 1200.0);
        seedBed("General Ward", "GEN-003", null, 1200.0);
        seedBed("ICU", "ICU-001", patient3, 4500.0);
        seedBed("ICU", "ICU-002", null, 4500.0);
        seedBed("Maternity", "MAT-001", null, 2000.0);
        seedBed("Maternity", "MAT-002", null, 2000.0);
        seedBed("Pediatric", "PED-001", patient2, 1500.0);

        // ---- Blood bank ----
        seedBlood("O+", 12, "Karan Malhotra", "9876512340", LocalDate.now().minusDays(20));
        seedBlood("A+", 8, "Fatima Sheikh", "9812355678", LocalDate.now().minusDays(35));
        seedBlood("B+", 5, "Aditya Joshi", "9988799655", LocalDate.now().minusDays(15));
        seedBlood("O-", 3, "Reema Kohli", "9765488109", LocalDate.now().minusDays(40));
        seedBlood("AB+", 4, "Manoj Deshmukh", "9900112233", LocalDate.now().minusDays(10));

        // ---- Appointments ----
        Appointment appt1 = new Appointment();
        appt1.setPatient(patient1);
        appt1.setDoctor(doctor1);
        appt1.setAppointmentDate(LocalDate.now().plusDays(1));
        appt1.setAppointmentTime(LocalTime.of(9, 30));
        appt1.setReason("Chest pain follow-up");
        appt1.setStatus("Confirmed");
        appointmentRepository.save(appt1);

        Appointment appt2 = new Appointment();
        appt2.setPatient(patient2);
        appt2.setDoctor(doctor2);
        appt2.setAppointmentDate(LocalDate.now().plusDays(1));
        appt2.setAppointmentTime(LocalTime.of(10, 0));
        appt2.setReason("Routine checkup");
        appt2.setStatus("Pending");
        appointmentRepository.save(appt2);

        // ---- Billing ----
        Billing bill1 = new Billing();
        bill1.setPatient(patient1);
        bill1.setDescription("Consultation - Cardiology, ECG Test");
        bill1.setAmount(new BigDecimal("2000"));
        bill1.setAmountPaid(new BigDecimal("2000"));
        bill1.setStatus("Paid");
        bill1.setBillDate(LocalDate.now().minusDays(3));
        bill1.setItemsJson("[{\"desc\":\"Consultation - Cardiology\",\"qty\":1,\"amount\":800},{\"desc\":\"ECG Test\",\"qty\":1,\"amount\":1200}]");
        billingRepository.save(bill1);

        Billing bill2 = new Billing();
        bill2.setPatient(patient2);
        bill2.setDescription("Consultation - Pediatrics");
        bill2.setAmount(new BigDecimal("600"));
        bill2.setAmountPaid(BigDecimal.ZERO);
        bill2.setStatus("Unpaid");
        bill2.setBillDate(LocalDate.now().minusDays(1));
        bill2.setItemsJson("[{\"desc\":\"Consultation - Pediatrics\",\"qty\":1,\"amount\":600}]");
        billingRepository.save(bill2);

        // ---- Blood requests ----
        BloodRequest req1 = new BloodRequest();
        req1.setPatient(patient3);
        req1.setBloodGroup("B-");
        req1.setUnits(2);
        req1.setWard("ICU");
        req1.setStatus("Urgent");
        bloodRequestRepository.save(req1);

        BloodRequest req2 = new BloodRequest();
        req2.setPatient(patient2);
        req2.setBloodGroup("A+");
        req2.setUnits(1);
        req2.setWard("General Ward");
        req2.setStatus("Requested");
        bloodRequestRepository.save(req2);

        // ---- Insurance ----
        Insurance insurance1 = new Insurance();
        insurance1.setPatient(patient1);
        insurance1.setProvider("Star Health Insurance");
        insurance1.setPolicyNumber("SH-2026-001122");
        insurance1.setCoverageAmount(new BigDecimal("500000"));
        insurance1.setValidFrom(LocalDate.of(2026, 1, 1));
        insurance1.setValidTo(LocalDate.of(2026, 12, 31));
        insuranceRepository.save(insurance1);

        // ---- Feedback ----
        Feedback feedback1 = new Feedback();
        feedback1.setPatient(patient1);
        feedback1.setDoctor(doctor1);
        feedback1.setRating(5);
        feedback1.setComments("Excellent care from Dr. Johnson, very thorough consultation.");
        feedback1.setFeedbackDate(LocalDate.now().minusDays(2));
        feedbackRepository.save(feedback1);
    }

    /** Creates the very first administrator from environment variables, so a production
     *  database is never left with either zero logins or well-known demo logins. */
    private void bootstrapAdmin() {
        if (bootstrapUsername.isBlank() || bootstrapPassword.isBlank() || bootstrapEmail.isBlank()) {
            log.warn("No users exist and BOOTSTRAP_ADMIN_USERNAME / BOOTSTRAP_ADMIN_PASSWORD / BOOTSTRAP_ADMIN_EMAIL "
                    + "are not all set - nobody will be able to sign in. Set them and restart to create the first admin.");
            return;
        }
        if (bootstrapPassword.length() < 8) {
            log.error("BOOTSTRAP_ADMIN_PASSWORD must be at least 8 characters - first admin NOT created.");
            return;
        }
        userRepository.save(new User(bootstrapUsername.trim(), passwordEncoder.encode(bootstrapPassword),
                bootstrapEmail.trim(), bootstrapFullName, Role.ADMIN));
        log.info("Created first administrator '{}'. Remove BOOTSTRAP_ADMIN_PASSWORD from the environment now.", bootstrapUsername.trim());
    }

    private void seedBed(String ward, String label, Patient patient, double chargePerDay) {
        RoomBed bed = new RoomBed();
        bed.setRoomNumber(label);
        bed.setBedNumber(label);
        bed.setRoomType(ward);
        bed.setStatus(patient != null ? "Occupied" : "Available");
        bed.setPatient(patient);
        bed.setChargePerDay(chargePerDay);
        roomBedRepository.save(bed);
    }

    private void seedBlood(String group, int units, String donorName, String donorContact, LocalDate lastDonation) {
        BloodBank entry = new BloodBank();
        entry.setBloodGroup(group);
        entry.setUnitsAvailable(units);
        entry.setDonorName(donorName);
        entry.setDonorContact(donorContact);
        entry.setLastDonationDate(lastDonation);
        entry.setExpiryDate(lastDonation.plusDays(42));
        entry.setStatus(units > 4 ? "Available" : "Low Stock");
        bloodBankRepository.save(entry);
    }
}
