-- Menu + audit tables (MySQL). Safe to run on an existing database: nothing is dropped.
CREATE TABLE IF NOT EXISTS menu_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role VARCHAR(20) NOT NULL,
  group_label VARCHAR(40),
  label VARCHAR(40) NOT NULL,
  url VARCHAR(80) NOT NULL,
  icon VARCHAR(30) DEFAULT 'dot',
  sort_order INT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uq_menu_role_url (role, url),
  KEY idx_menu_role (role, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_name VARCHAR(64) NOT NULL,
  record_id BIGINT NOT NULL,
  operation VARCHAR(10) NOT NULL,
  user_id BIGINT NULL,
  username VARCHAR(100) NULL,
  old_values TEXT NULL,
  new_values TEXT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_audit_user (user_id),
  KEY idx_audit_record (table_name, record_id),
  KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Default menu (INSERT IGNORE skips rows that already exist for a role+page).
INSERT IGNORE INTO menu_items (role, group_label, label, url, icon, sort_order, active) VALUES
('ADMIN','OVERVIEW','Dashboard','admin-dashboard.html','dashboard',10,TRUE),
('ADMIN','CARE MODULES','Patients','patient-management.html','patients',20,TRUE),
('ADMIN','CARE MODULES','Doctors','doctor-management.html','doctors',30,TRUE),
('ADMIN','CARE MODULES','Appointments','appointment-management.html','calendar',40,TRUE),
('ADMIN','CARE MODULES','Room & Bed','room-bed-management.html','bed',50,TRUE),
('ADMIN','CARE MODULES','Blood Bank','blood-bank-management.html','blood',60,TRUE),
('ADMIN','OPERATIONS','Billing','billing-payment.html','billing',70,TRUE),
('ADMIN','OPERATIONS','Insurance','insurance-management.html','shield',80,TRUE),
('ADMIN','OPERATIONS','Government Schemes','schemes-management.html','bank',90,TRUE),
('ADMIN','OPERATIONS','Reports','reports-analytics.html','chart',100,TRUE),
('ADMIN','OPERATIONS','Feedback','feedback.html','feedback',110,TRUE),
('ADMIN','OPERATIONS','Staff Activity','staff-activity.html','clock',120,TRUE),
('ADMIN','ADMINISTRATION','Staff Accounts','staff-management.html','users',130,TRUE),
('RECEPTION','OVERVIEW','Dashboard','reception-dashboard.html','dashboard',10,TRUE),
('RECEPTION','FRONT DESK','Patients','reception-patients.html','patients',20,TRUE),
('RECEPTION','FRONT DESK','Appointments','reception-appointments.html','calendar',30,TRUE),
('RECEPTION','FRONT DESK','Room & Bed','reception-room-bed.html','bed',40,TRUE),
('RECEPTION','FRONT DESK','Billing','reception-billing.html','billing',50,TRUE),
('RECEPTION','FRONT DESK','Insurance','reception-insurance.html','shield',60,TRUE),
('RECEPTION','FRONT DESK','Government Schemes','reception-schemes.html','bank',70,TRUE),
('DOCTOR','OVERVIEW','Dashboard','doctor-dashboard.html','dashboard',10,TRUE),
('DOCTOR','MY CARE','My Patients','doctor-patients.html','patients',20,TRUE),
('DOCTOR','MY CARE','My Appointments','doctor-appointments.html','calendar',30,TRUE),
('DOCTOR','MY CARE','Feedback','doctor-feedback.html','feedback',40,TRUE),
('PATIENT','OVERVIEW','Dashboard','patient-dashboard.html','dashboard',10,TRUE),
('PATIENT','MY CARE','My Appointments','patient-appointments.html','calendar',20,TRUE),
('PATIENT','MY CARE','My Bills','patient-billing.html','billing',30,TRUE),
('PATIENT','MY CARE','Insurance','patient-insurance.html','shield',40,TRUE),
('PATIENT','MY CARE','Government Schemes','patient-schemes.html','bank',50,TRUE),
('PATIENT','MY CARE','Feedback','patient-feedback.html','feedback',60,TRUE),
('ADMIN','ADMINISTRATION','Audit Log','audit-log.html','audit',140,TRUE),
('ADMIN','ADMINISTRATION','Menu Management','menu-management.html','menu',150,TRUE);

-- Server-side logout support: adds users.tokens_valid_after_ms only if it is missing (safe to re-run).
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'tokens_valid_after_ms');
SET @ddl := IF(@col = 0, 'ALTER TABLE users ADD COLUMN tokens_valid_after_ms BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
