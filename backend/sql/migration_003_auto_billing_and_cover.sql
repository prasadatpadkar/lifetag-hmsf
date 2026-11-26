-- Auto-billing of room/bed, blood and treatments + sanction-based insurance / scheme cover (MySQL).
-- NOTE: with spring.jpa.hibernate.ddl-auto=update (the default) the application adds these columns itself
-- on start-up and the BillingDataMigrator fixes existing rows, so you normally do NOT need to run this
-- file. It is provided for installations that run with ddl-auto=validate/none. Back up first.
-- ALTER ... ADD COLUMN is not idempotent in MySQL: run each statement once (skip any column that exists).

-- Invoices: cover breakdown, awaiting-sanction amount, refund due, and the records an invoice was built from
ALTER TABLE billings MODIFY insurance_note VARCHAR(500) NULL;
ALTER TABLE billings MODIFY items_json MEDIUMTEXT NULL;
ALTER TABLE billings ADD COLUMN coverage_json TEXT NULL;
ALTER TABLE billings ADD COLUMN coverage_pending DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE billings ADD COLUMN refund_due DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE billings ADD COLUMN source_refs_json TEXT NULL;
CREATE INDEX idx_billings_admission ON billings (admission_id);

-- Admissions: bed days already invoiced
ALTER TABLE admissions ADD COLUMN billed_up_to DATE NULL;
CREATE INDEX idx_admissions_patient_status ON admissions (patient_id, status);

-- Blood bank: price per unit, and what each request was issued/charged as
ALTER TABLE blood_bank ADD COLUMN price_per_unit DECIMAL(10,2) NOT NULL DEFAULT 1500.00;
ALTER TABLE blood_requests ADD COLUMN requested_by VARCHAR(100) NULL;
ALTER TABLE blood_requests ADD COLUMN admission_id BIGINT NULL;
ALTER TABLE blood_requests ADD COLUMN fulfilled_date DATE NULL;
ALTER TABLE blood_requests ADD COLUMN price_per_unit DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE blood_requests ADD COLUMN total_charge DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE blood_requests ADD COLUMN billed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE blood_requests ADD CONSTRAINT fk_bloodreq_admission FOREIGN KEY (admission_id) REFERENCES admissions(id);

-- Schemes: fixed yearly cover ceiling (e.g. Ayushman Bharat / MJPJAY 5,00,000)
ALTER TABLE schemes ADD COLUMN max_cover_amount DECIMAL(12,2) NULL;
UPDATE schemes SET max_cover_amount = 500000
 WHERE max_cover_amount IS NULL AND (LOWER(name) LIKE '%ayushman%' OR LOWER(name) LIKE '%jan arogya%' OR LOWER(name) LIKE '%pmjay%');

-- Cover is derived from SANCTIONED claims only. Earlier data deducted cover for claims that were merely
-- submitted / under review: clear it, the application recomputes it from the claims on next start.
UPDATE billings SET insurance_covered = 0 WHERE insurance_covered > 0;
UPDATE insurance_claims SET approved_amount = 0 WHERE status <> 'Approved';
UPDATE scheme_claims SET sanctioned_amount = 0 WHERE status <> 'Approved';

-- Row-size safe: JSON columns must be TEXT (VARCHAR(30000)+ overflows MySQL's 65,535-byte row limit under utf8mb4)
ALTER TABLE billings MODIFY payments_json TEXT NULL;
