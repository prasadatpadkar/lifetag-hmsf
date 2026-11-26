-- NOTE: after this script also run migration_001_audit_and_menu.sql (audit_log + menu_items).
-- ============================================================================
-- !!  DEVELOPMENT / DEMO ONLY - DO NOT USE FOR A REAL DEPLOYMENT  !!
--   * It inserts demo logins (including two ADMIN accounts) whose passwords are
--     published in CREDENTIALS.md.
--   * It is OUT OF DATE: it does not create insurance_claims, scheme_claims or
--     insurance_providers, which the current backend needs.
-- For production let the backend create the schema (spring.jpa.hibernate.ddl-auto=update)
-- and create the first admin with the BOOTSTRAP_ADMIN_* variables (see docs/deployment-guide.md).
-- ============================================================================
-- ============================================================================
-- LifeTag HMS — Full MySQL schema + demo data
-- ============================================================================
-- Run this once directly against MySQL to build the whole database yourself,
-- instead of letting Hibernate auto-create tables on app startup.
--
-- Usage:
--   mysql -u root -p < backend/sql/lifetag_hms_full.sql
--
-- This creates the `lifetag_hms` database, every table the app needs, and
-- seeds the same demo accounts/records as DataSeeder.java (so both paths
-- produce an equivalent database — pick whichever you prefer).
--
-- After running this, tell the Spring Boot app not to re-manage the schema:
-- in backend/src/main/resources/application-mysql.properties, set
--   spring.jpa.hibernate.ddl-auto=validate
-- (already defaults to `update`, which just adds anything missing and won't
-- touch/drop what's here — but `validate` is safer once you own the schema).
-- ============================================================================

DROP DATABASE IF EXISTS lifetag_hms;
CREATE DATABASE lifetag_hms CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE lifetag_hms;

-- Optional dedicated app user — uncomment and set a real password if you
-- don't want to connect as root from the backend.
-- CREATE USER IF NOT EXISTS 'lifetag_user'@'localhost' IDENTIFIED BY 'change-me';
-- GRANT ALL PRIVILEGES ON lifetag_hms.* TO 'lifetag_user'@'localhost';
-- FLUSH PRIVILEGES;

-- ============================================================================
-- TABLES
-- ============================================================================

CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password      VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    full_name     VARCHAR(255),
    role          VARCHAR(20)  NOT NULL,              -- ADMIN | DOCTOR | RECEPTION | PATIENT
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE patients (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    age                 INT,
    gender              VARCHAR(20),
    contact             VARCHAR(50),
    email               VARCHAR(255),
    address             VARCHAR(500),
    blood_group         VARCHAR(10),
    emergency_contact    VARCHAR(50),
    registration_date   DATE         NOT NULL DEFAULT (CURRENT_DATE),
    status              VARCHAR(20)  NOT NULL DEFAULT 'Active',
    user_id             BIGINT UNIQUE,
    CONSTRAINT fk_patients_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE doctors (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    specialization    VARCHAR(255),
    department        VARCHAR(255),
    contact           VARCHAR(50),
    email             VARCHAR(255),
    qualification     VARCHAR(255),
    experience_years  INT,
    available_days    VARCHAR(255),
    status            VARCHAR(20)  NOT NULL DEFAULT 'Active',
    user_id           BIGINT UNIQUE,
    CONSTRAINT fk_doctors_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE appointments (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id         BIGINT NOT NULL,
    doctor_id          BIGINT NOT NULL,
    appointment_date   DATE,
    appointment_time   TIME,
    reason             VARCHAR(500),
    status             VARCHAR(20) NOT NULL DEFAULT 'Scheduled',  -- Scheduled | Pending | Confirmed | Waiting | Completed | Cancelled
    notes              VARCHAR(1000),
    CONSTRAINT fk_appt_patient FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE CASCADE,
    CONSTRAINT fk_appt_doctor  FOREIGN KEY (doctor_id)  REFERENCES doctors(id)  ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE billings (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id       BIGINT NOT NULL,
    description      VARCHAR(500),
    amount           DECIMAL(12,2),
    discount         DECIMAL(12,2) NOT NULL DEFAULT 0,
    amount_paid      DECIMAL(12,2) NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL DEFAULT 'Pending',   -- Paid | Unpaid | Pending | Partial | Cancelled
    payment_method   VARCHAR(50),
    items_json       VARCHAR(4000),
    bill_date        DATE NOT NULL DEFAULT (CURRENT_DATE),
    CONSTRAINT fk_billing_patient FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE rooms_beds (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_number      VARCHAR(50) NOT NULL,
    bed_number       VARCHAR(50),
    room_type        VARCHAR(50),                              -- General Ward | ICU | Maternity | Pediatric
    status           VARCHAR(20) NOT NULL DEFAULT 'Available',  -- Available | Occupied | Cleaning | Maintenance
    patient_id       BIGINT,
    charge_per_day   DOUBLE,
    CONSTRAINT fk_bed_patient FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE blood_bank (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    blood_group          VARCHAR(5) NOT NULL,
    units_available      INT NOT NULL DEFAULT 0,
    donor_name           VARCHAR(255),
    donor_contact        VARCHAR(50),
    last_donation_date   DATE,
    expiry_date          DATE,
    status               VARCHAR(20) NOT NULL DEFAULT 'Available'  -- Available | Low Stock
) ENGINE=InnoDB;

CREATE TABLE blood_requests (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id     BIGINT NOT NULL,
    blood_group    VARCHAR(5) NOT NULL,
    units          INT NOT NULL DEFAULT 1,
    ward           VARCHAR(100),
    status         VARCHAR(20) NOT NULL DEFAULT 'Requested',  -- Requested | Urgent | Fulfilled | Cancelled
    request_date   DATE NOT NULL DEFAULT (CURRENT_DATE),
    CONSTRAINT fk_bloodreq_patient FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE schemes (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    description    VARCHAR(2000),
    eligibility    VARCHAR(500),
    benefits       VARCHAR(500),
    category       VARCHAR(100),                          -- Government | Hospital
    status         VARCHAR(20) NOT NULL DEFAULT 'Active'
) ENGINE=InnoDB;

CREATE TABLE patient_schemes (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id        BIGINT NOT NULL,
    scheme_id         BIGINT NOT NULL,
    enrollment_date   DATE NOT NULL DEFAULT (CURRENT_DATE),
    status            VARCHAR(20) NOT NULL DEFAULT 'Enrolled',  -- Enrolled | Approved | Rejected | Expired
    CONSTRAINT fk_ps_patient FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE CASCADE,
    CONSTRAINT fk_ps_scheme  FOREIGN KEY (scheme_id)  REFERENCES schemes(id)  ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE insurance (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id        BIGINT NOT NULL,
    provider          VARCHAR(255),
    policy_number     VARCHAR(100),
    coverage_amount   DECIMAL(12,2),
    valid_from        DATE,
    valid_to          DATE,
    status            VARCHAR(20) NOT NULL DEFAULT 'Active',  -- Active | Expired | Claimed | Pending
    CONSTRAINT fk_insurance_patient FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE feedback (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id     BIGINT NOT NULL,
    doctor_id      BIGINT,
    rating         INT,
    comments       VARCHAR(2000),
    feedback_date  DATE NOT NULL DEFAULT (CURRENT_DATE),
    CONSTRAINT fk_feedback_patient FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_doctor  FOREIGN KEY (doctor_id)  REFERENCES doctors(id)  ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE staff_activity (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    staff_name   VARCHAR(255),
    role         VARCHAR(50),
    action       VARCHAR(50),
    details      VARCHAR(1000),
    timestamp    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE notifications (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    title       VARCHAR(255) NOT NULL,
    message     VARCHAR(1000),
    type        VARCHAR(50) DEFAULT 'SYSTEM',
    link        VARCHAR(255),
    is_read     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Helpful indexes on the foreign keys used by list/filter queries
CREATE INDEX idx_appt_patient   ON appointments(patient_id);
CREATE INDEX idx_appt_doctor    ON appointments(doctor_id);
CREATE INDEX idx_billing_patient ON billings(patient_id);
CREATE INDEX idx_bed_patient    ON rooms_beds(patient_id);
CREATE INDEX idx_bloodreq_patient ON blood_requests(patient_id);
CREATE INDEX idx_ps_patient     ON patient_schemes(patient_id);
CREATE INDEX idx_insurance_patient ON insurance(patient_id);
CREATE INDEX idx_feedback_patient ON feedback(patient_id);
CREATE INDEX idx_notification_user ON notifications(user_id);

-- ============================================================================
-- SEED DATA
-- ============================================================================

-- ---- Users ----
-- Real named logins, several per role. BCrypt hashes correspond to the plaintext
-- passwords listed in credentials.md (keep that file private / rotate before go-live).
-- (Spring Security's BCryptPasswordEncoder reads $2a/$2b/$2y hashes interchangeably.)
INSERT INTO users (id, username, password, email, full_name, role, enabled) VALUES
(1,  'rajesh.mehta',      '$2b$10$b9IpEFzjgpv.bvlzHrUgfuZA559zmcnYmX56WF3PDHo/6O8gf8njG', 'rajesh.mehta@lifetag.com',      'Rajesh Mehta',          'ADMIN',     TRUE),
(2,  'anita.deshmukh',    '$2b$10$V1jQ.s.l.rzBOAA.CT8x0.2HkVJUTv0s8otCjTvB5aD3OrJgaJErm', 'anita.deshmukh@lifetag.com',    'Anita Deshmukh',        'ADMIN',     TRUE),
(3,  'neha.kapoor',       '$2b$10$E/OEHTn8Jrpig4/Txa0NXOL8/hi4FAbPH9atK2JEe/QWxRjsnzila', 'neha.kapoor@lifetag.com',       'Neha Kapoor',           'RECEPTION', TRUE),
(4,  'suresh.pillai',     '$2b$10$JTjcZqr.a0ncwJgNCLiAU.f.74pcXHkiYHln118XNlHS/SL/fxAgW', 'suresh.pillai@lifetag.com',     'Suresh Pillai',         'RECEPTION', TRUE),
(5,  'sarah.johnson',     '$2b$10$smHebhQAGidasAJTUMB6cuf/oUvFgaYMc9H8vQl1Wdxx.BwqWRZ4G', 'sarah.johnson@lifetag.com',     'Dr. Sarah Johnson',     'DOCTOR',    TRUE),
(6,  'arvind.iyer',       '$2b$10$dLK6JKMUO/y6KcdAZJE.KODmLSlb2lg/iqlNzIode8RXjWe1.DS3K', 'arvind.iyer@lifetag.com',       'Dr. Arvind Iyer',       'DOCTOR',    TRUE),
(7,  'melissa.fernandes', '$2b$10$CT8mYotsDKWfWouNWKbtSOD4SijAFH5T1E9CdWZxHxDC4E3LASeBa', 'melissa.f@lifetag.com',         'Dr. Melissa Fernandes', 'DOCTOR',    TRUE),
(8,  'john.doe',          '$2b$10$u5JekQKssvjT1eK/lrWDJOqAaWXtzQ5CmSVcrF9zOSZ3PGGj3jsui', 'john.doe@lifetag.com',          'John Doe',              'PATIENT',   TRUE),
(9,  'priya.kulkarni',    '$2b$10$Eg1SToZUXSSMGoVdQl6f0eykDV9th5nqScUfAEyuyvHDJQKd3Mx1G', 'priya.kulkarni@lifetag.com',    'Priya Kulkarni',        'PATIENT',   TRUE),
(10, 'arjun.nair',        '$2b$10$VzHiEn78q7woJBmbmVz.H.cQnsHuwzleOZNmXjUOAlWmZgpf8yC32', 'arjun.nair@lifetag.com',        'Arjun Nair',            'PATIENT',   TRUE);

-- ---- Patients ----
INSERT INTO patients (id, name, age, gender, contact, email, address, blood_group, emergency_contact, status, user_id) VALUES
(1, 'John Doe',        34, 'Male',   '9123456780', 'john.doe@lifetag.com',       'Pune, Maharashtra',       'O+', '9000011223', 'Active', 8),
(2, 'Priya Kulkarni',   8, 'Female', '9812345678', 'priya.kulkarni@lifetag.com', '4 Lake View, Sangli',     'A+', '9000011224', 'Active', 9),
(3, 'Arjun Nair',      47, 'Male',   '9988776655', 'arjun.nair@lifetag.com',     '78 Station Road, Sangli', 'B-', '9811122334', 'Active', 10);

-- ---- Doctors ----
INSERT INTO doctors (id, name, specialization, department, contact, email, qualification, experience_years, available_days, status, user_id) VALUES
(1, 'Dr. Sarah Johnson',    'Cardiology',   'Cardiology',   '9876543210', 'sarah.johnson@lifetag.com', 'MD, DM Cardiology', 12, 'Mon, Wed, Fri', 'Active',   5),
(2, 'Dr. Arvind Iyer',      'Pediatrics',   'Pediatrics',   '9812345678', 'arvind.iyer@lifetag.com',   'MD Pediatrics',      8, 'Tue, Thu, Sat', 'Active',   6),
(3, 'Dr. Melissa Fernandes','Orthopedics',  'Orthopedics',  '9988776655', 'melissa.f@lifetag.com',     'MS Orthopedics',    15, 'Mon, Tue, Fri', 'On Leave', 7);

-- ---- Schemes ----
INSERT INTO schemes (id, name, description, eligibility, benefits, category, status) VALUES
(1, 'Ayushman Bharat',
    'National health protection scheme offering coverage up to 5 lakh per family per year.',
    'Below poverty line families', 'Cashless treatment up to Rs. 5,00,000', 'Government', 'Active'),
(2, 'Senior Citizen Health Scheme',
    'Special discounted healthcare package for senior citizens.',
    'Age 60 and above', '20% discount on consultations and diagnostics', 'Hospital', 'Active');

-- ---- Rooms & beds ----
INSERT INTO rooms_beds (id, room_number, bed_number, room_type, status, patient_id, charge_per_day) VALUES
(1, 'GEN-001', 'GEN-001', 'General Ward', 'Occupied',  1,    1200.0),
(2, 'GEN-002', 'GEN-002', 'General Ward', 'Available', NULL, 1200.0),
(3, 'GEN-003', 'GEN-003', 'General Ward', 'Available', NULL, 1200.0),
(4, 'ICU-001',  'ICU-001', 'ICU',          'Occupied',  3,    4500.0),
(5, 'ICU-002',  'ICU-002', 'ICU',          'Available', NULL, 4500.0),
(6, 'MAT-001',  'MAT-001', 'Maternity',    'Available', NULL, 2000.0),
(7, 'MAT-002',  'MAT-002', 'Maternity',    'Occupied',  2,    2000.0),
(8, 'PED-001',  'PED-001', 'Pediatric',    'Available', NULL, 1500.0);

-- ---- Blood bank (donor entries / stock) ----
INSERT INTO blood_bank (id, blood_group, units_available, donor_name, donor_contact, last_donation_date, expiry_date, status) VALUES
(1, 'O+',  12, 'Karan Malhotra',   '9876512340', DATE_SUB(CURDATE(), INTERVAL 20 DAY), DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 20 DAY), INTERVAL 42 DAY), 'Available'),
(2, 'A+',   8, 'Fatima Sheikh',    '9812355678', DATE_SUB(CURDATE(), INTERVAL 35 DAY), DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 35 DAY), INTERVAL 42 DAY), 'Available'),
(3, 'B+',   5, 'Aditya Joshi',     '9988799655', DATE_SUB(CURDATE(), INTERVAL 15 DAY), DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 15 DAY), INTERVAL 42 DAY), 'Available'),
(4, 'O-',   3, 'Reema Kohli',      '9765488109', DATE_SUB(CURDATE(), INTERVAL 40 DAY), DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 40 DAY), INTERVAL 42 DAY), 'Low Stock'),
(5, 'AB+',  4, 'Manoj Deshmukh',   '9900112233', DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 10 DAY), INTERVAL 42 DAY), 'Low Stock');

-- ---- Blood requests ----
INSERT INTO blood_requests (id, patient_id, blood_group, units, ward, status, request_date) VALUES
(1, 3, 'B-', 2, 'ICU',          'Urgent',    CURDATE()),
(2, 2, 'A+', 1, 'General Ward', 'Requested', CURDATE());

-- ---- Appointments ----
INSERT INTO appointments (id, patient_id, doctor_id, appointment_date, appointment_time, reason, status) VALUES
(1, 1, 1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), '09:30:00', 'Chest pain follow-up', 'Confirmed'),
(2, 2, 2, DATE_ADD(CURDATE(), INTERVAL 1 DAY), '10:00:00', 'Routine checkup',      'Pending');

-- ---- Billing ----
INSERT INTO billings (id, patient_id, description, amount, discount, amount_paid, status, payment_method, items_json, bill_date) VALUES
(1, 1, 'Consultation - Cardiology, ECG Test', 2000.00, 0, 2000.00, 'Paid',   'Cash', '[{"desc":"Consultation - Cardiology","qty":1,"amount":800},{"desc":"ECG Test","qty":1,"amount":1200}]', DATE_SUB(CURDATE(), INTERVAL 3 DAY)),
(2, 2, 'Consultation - Pediatrics',           600.00,  0, 0.00,    'Unpaid', NULL,   '[{"desc":"Consultation - Pediatrics","qty":1,"amount":600}]', DATE_SUB(CURDATE(), INTERVAL 1 DAY));

-- ---- Insurance ----
INSERT INTO insurance (id, patient_id, provider, policy_number, coverage_amount, valid_from, valid_to, status) VALUES
(1, 1, 'Star Health Insurance', 'SH-2026-001122', 500000.00, '2026-01-01', '2026-12-31', 'Active');

-- ---- Feedback ----
INSERT INTO feedback (id, patient_id, doctor_id, rating, comments, feedback_date) VALUES
(1, 1, 1, 5, 'Excellent care from Dr. Johnson, very thorough consultation.', DATE_SUB(CURDATE(), INTERVAL 2 DAY));

-- ---- Staff activity (a couple of seed entries so the log isn't empty) ----
INSERT INTO staff_activity (staff_name, role, action, details, timestamp) VALUES
('Hospital Admin', 'Admin', 'Created', 'Patient: John Doe', DATE_SUB(NOW(), INTERVAL 2 DAY)),
('Front Desk',     'Reception', 'Created', 'Appointment: John Doe with Dr. Sarah Johnson', DATE_SUB(NOW(), INTERVAL 1 DAY));

-- ---- Notifications (welcome notification for every seeded login) ----
INSERT INTO notifications (user_id, title, message, type, link, is_read, created_at) VALUES
(1,  'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW()),
(2,  'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW()),
(3,  'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW()),
(4,  'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW()),
(5,  'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW()),
(6,  'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW()),
(7,  'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW()),
(8,  'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW()),
(9,  'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW()),
(10, 'Welcome to LifeTag HMS', 'Your account is set up and ready to go.', 'SYSTEM', NULL, FALSE, NOW());

-- ============================================================================
-- Done. Sanity check:
--   SHOW TABLES;
--   SELECT username, role FROM users;
-- ============================================================================
