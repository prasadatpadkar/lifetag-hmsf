-- Clinical records + itemised billing (MySQL).
-- NOTE: with spring.jpa.hibernate.ddl-auto=update (the default) the application creates all of this
-- itself on start-up, so you normally do NOT need to run this file. It documents the schema and is
-- provided for installations that run with ddl-auto=validate/none. Back up first. ALTER ... ADD COLUMN
-- is not idempotent in MySQL: run each statement once (skip any column that already exists).

CREATE TABLE IF NOT EXISTS vitals (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  patient_id BIGINT NOT NULL,
  recorded_at DATETIME NOT NULL,
  recorded_by VARCHAR(100), systolic INT, diastolic INT, pulse INT,
  temperature_c DOUBLE, spo2 INT, respiratory_rate INT, weight_kg DOUBLE, height_cm DOUBLE,
  blood_sugar INT, sugar_type VARCHAR(30), notes VARCHAR(500),
  KEY idx_vitals_patient (patient_id, recorded_at),
  CONSTRAINT fk_vitals_patient FOREIGN KEY (patient_id) REFERENCES patients(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS diagnostic_reports (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  patient_id BIGINT NOT NULL, doctor_id BIGINT NULL,
  category VARCHAR(40), report_type VARCHAR(60), title VARCHAR(150), report_date DATE,
  findings VARCHAR(3000), impression VARCHAR(1500), result_status VARCHAR(20), status VARCHAR(30),
  performed_by VARCHAR(100), lab_name VARCHAR(120), results_json VARCHAR(4000), report_url VARCHAR(300),
  cost DECIMAL(12,2) DEFAULT 0, billed BOOLEAN NOT NULL DEFAULT FALSE,
  KEY idx_report_patient (patient_id, report_date),
  CONSTRAINT fk_report_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
  CONSTRAINT fk_report_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS treatments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  patient_id BIGINT NOT NULL, doctor_id BIGINT NULL, admission_id BIGINT NULL,
  diagnosis VARCHAR(200), diagnosis_code VARCHAR(20), treatment_type VARCHAR(40), procedure_name VARCHAR(200),
  plan VARCHAR(3000), medications_json VARCHAR(4000), start_date DATE, end_date DATE,
  status VARCHAR(20), outcome VARCHAR(300), follow_up_date DATE, notes VARCHAR(500),
  procedure_cost DECIMAL(12,2) DEFAULT 0, medication_cost DECIMAL(12,2) DEFAULT 0,
  billed BOOLEAN NOT NULL DEFAULT FALSE,
  KEY idx_treat_patient (patient_id, start_date),
  CONSTRAINT fk_treat_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
  CONSTRAINT fk_treat_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admissions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  patient_id BIGINT NOT NULL, doctor_id BIGINT NULL, room_bed_id BIGINT NULL,
  ward_type VARCHAR(40), room_number VARCHAR(20), bed_number VARCHAR(20),
  charge_per_day DECIMAL(12,2) DEFAULT 0, admitted_on DATE, discharged_on DATE,
  reason VARCHAR(300), diagnosis VARCHAR(200), status VARCHAR(20), discharge_summary VARCHAR(3000),
  billed BOOLEAN NOT NULL DEFAULT FALSE,
  KEY idx_adm_patient (patient_id, admitted_on),
  CONSTRAINT fk_adm_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
  CONSTRAINT fk_adm_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id),
  CONSTRAINT fk_adm_bed FOREIGN KEY (room_bed_id) REFERENCES rooms_beds(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing tables: new columns
ALTER TABLE patients     ADD COLUMN allergies VARCHAR(500), ADD COLUMN chronic_conditions VARCHAR(500);
ALTER TABLE doctors      ADD COLUMN consultation_fee DECIMAL(12,2);
ALTER TABLE appointments ADD COLUMN billed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE billings
  MODIFY COLUMN items_json VARCHAR(8000),
  ADD COLUMN subtotal DECIMAL(12,2), ADD COLUMN tax_amount DECIMAL(12,2) DEFAULT 0,
  ADD COLUMN insurance_covered DECIMAL(12,2) DEFAULT 0, ADD COLUMN insurance_note VARCHAR(200),
  ADD COLUMN doctor_id BIGINT NULL, ADD COLUMN admission_id BIGINT NULL, ADD COLUMN due_date DATE,
  ADD COLUMN notes VARCHAR(500), ADD COLUMN payments_json VARCHAR(4000);
