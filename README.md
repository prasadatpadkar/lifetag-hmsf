# LifeTag — Hospital Management System

A hospital management system with role-based portals (Admin, Doctor, Reception, Patient)
covering patients, doctors, appointments, billing, room/bed allocation, blood bank,
insurance, government/hospital schemes, feedback, and reporting.

## What's in this package

```
backend/    Spring Boot 3 REST API (Java 17, Spring Data JPA, Spring Security + JWT, MySQL)
frontend/   Static HTML/CSS/JS multi-page site (no build step), served by nginx in production
deploy/     docker-compose.yml wiring MySQL + backend + frontend together
docs/       Deployment guide
```

## Recent changes
See `docs/CHANGES-billing-and-cover.md` (auto-billing of room/bed/blood, sanction-based insurance & scheme cover).

## Fastest path to running it (Docker)

```bash
cd deploy
cp .env.example .env        # set JWT_SECRET and DB passwords
docker compose up -d --build
```

Frontend at `http://localhost`, API at `http://localhost/api` (proxied to the backend).
Full details, production checklist, and cloud-deploy notes are in `docs/deployment-guide.md`.

## Logins

- **Local development (MySQL):** no demo accounts are seeded by default. For a disposable demo database only, explicitly enable seeding and see `backend/CREDENTIALS.md`.
- **Real deployment (MySQL / docker-compose):** no demo accounts exist. Set
  `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD` and `BOOTSTRAP_ADMIN_EMAIL` in `deploy/.env`
  to create the first administrator - see `docs/deployment-guide.md`.

## Running without Docker (local dev)

**Backend** (requires MySQL 8.0+ and Java 17+):

1. Create the `lifetag_hms` database and a database user in MySQL.
2. In PowerShell, set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, and a random `JWT_SECRET` (at least 32 bytes). Set `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD`, and `BOOTSTRAP_ADMIN_EMAIL` for the first admin.
3. Run:
```powershell
cd backend
mvn spring-boot:run
```

API on `http://localhost:8080`. See `backend/README.md` for exact setup commands and the full API table.

**Frontend** — just open `frontend/index.html` in a browser, or serve the folder with any
static server (VS Code Live Server, `npx http-server frontend -p 5500`, or `npm run dev`
via the included Vite config — see below). `js/api-config.js` auto-detects local dev vs.
production and points at `http://localhost:8080/api` accordingly.

### Running the frontend through Vite

```bash
cd frontend
npm install
npm run dev
```
Opens automatically at `http://localhost:5500` with live reload on file changes. This is
just a fast static dev server for the existing plain HTML/CSS/JS — there's no React/build
step involved, `npm run build` also works if you ever want a minified static bundle in
`frontend/dist/` (the Docker image doesn't use this; it serves the raw files via nginx).

## Clinical records, itemised invoices and large dataset (latest update)

* **Doctor view of a patient's history** - *My Patients > Full record* shows BP / pulse / sugar charts, every previous
  report (MRI, CT, X-ray, ECG, echo, blood tests with reference ranges and H/L flags), treatments with medicines,
  admissions and visits. Doctors can add vitals, reports and treatments; they can only open patients they have an
  appointment with. Admin sees the same page from *Patients*; patients get read-only *Medical Records*.
* **Itemised invoices** - `invoice.html?id=` prints a hospital-style bill: charges grouped by category (doctor fees,
  room & bed, nursing, radiology, laboratory, cardiac, procedures, OT, pharmacy, blood bank, ambulance...), GST,
  discount, insurance cover, payments received, balance and a "where the money went" breakdown. Billing screens can
  *Import charges* (consultations, room days x daily rate, reports, procedures, medicines not yet billed) and record
  part payments. Totals are calculated on the server.
* **Admissions** - reception/admin register: admit to a bed, discharge, days stayed and room charges feed the invoice.
* **Reports** - `GET /api/reports/revenue-by-category`; pending amounts now respect insurance and payments.
* **Dataset** - see `backend/CREDENTIALS.md` (shared password `Demo@2026!`). Disable with `SEED_LARGE_DATASET=false`.
* **Schema** - created automatically by `ddl-auto=update`; `backend/sql/migration_002_clinical_and_billing.sql` documents it.
  Demo data is loaded only when explicitly enabled on a disposable MySQL database.
* **Not compiled here:** the Java code of this update was only syntax-checked with `javac` (Maven Central was blocked in
  the build environment). Run `cd backend && mvn clean package` first and report any compile error.

## HOD requirements update (earlier version)

This project is a **Spring Boot (Java) + static HTML/JS** application, so the PHP-specific items were
implemented with their Java equivalents; the stack was not changed.

| # | Requirement | What was done |
|---|-------------|---------------|
| 1, 2 | Sanitization, PDO / prepared queries | The backend contains **no hand-written SQL**: every query goes through Spring Data JPA / Hibernate, which sends all values as bound parameters (the Java equivalent of PDO prepared statements). The audit writer uses a `?`-parameterized `JdbcTemplate` insert. Server-side validation (Bean Validation) was added to the entities: required names, max lengths, e-mail format, phone pattern, age/rating/units ranges, non-negative money. Failures return a clear `400`. Id fields read from JSON bodies (`patientId`, `policyId`, …) are checked by a shared helper, so a missing or malformed id gives a 400 instead of a server error. Billing, claims, insurance, schemes and staff entities got the same limits. The frontend already escapes all output (`escapeHtml`) and has `required`/`pattern` attributes on forms. |
| 3 | Lightweight CSS | All `transition`, `animation`, `@keyframes`, `will-change` and decorative hover transforms removed; Google Fonts (3 external font families) replaced by the system font stack; unused rules removed. CSS went from ~55.2 KB to ~53.7 KB and no longer needs any external request. |
| 4 | Avoid unnecessary JS | Reviewed every script: no orphaned files and no uncalled functions were found, so none were removed. New code adds only three small scripts (`menu.js`, `audit-log.js`, `menu-management.js`) and no libraries. Chart.js is only loaded by the Reports page. |
| 5 | Common login | One login page for all roles (existing): BCrypt passwords, JWT sessions, login throttling, role redirect, logout clears the session. Roles are enforced on the server (`SecurityConfig` + per-controller checks); the frontend page guard sends other roles away from a URL typed directly. New admin-only routes `/api/audit-logs/**` and `/api/admin/**` were added to the server rules. **Logout is now enforced on the server too** (`POST /api/auth/logout`): every token issued before the logout (or before a password change) is rejected, not just deleted from the browser. LOGIN, LOGOUT and LOGIN_FAIL events are written to the audit log (never the typed password or unknown user names). |
| 7 | Tables / consistency | All tables have primary keys and foreign keys (JPA entities). Every create/update/delete endpoint now runs in a **transaction** (`@Transactional`), so e.g. creating a patient + login account + notification is all-or-nothing. |
| 8 | Audit logs | New `audit_log` table filled **automatically** by Hibernate event listeners (`AuditEventListener`) for INSERT, UPDATE and DELETE on every business table: table, record ID, operation, user ID/username, timestamp, and old/new values as JSON (only changed fields on update). Passwords/tokens are never stored. Admin-only API `GET /api/audit-logs?userId=&recordId=&table=&operation=` and a new **Audit Log** page. The audit row is written in the same transaction as the change. One shared table with a `table_name` column is used instead of one table per module. |
| 9 | Responsive | Existing responsive layer kept; checked in a browser on **all 32 pages at 1366, 820, 390 and 320 px wide** (no horizontal scrolling, no unexpected redirects; drawer menu on phones). |
| 10 | Menu table + admin | New `menu_items` table, seeded once with the current sidebars. Sidebars are built from the database (`GET /api/menu`, by role). New **Menu Management** page (admin only) to add / edit / hide / delete entries, with server validation (label, `*.html` page name only, icon list, order, no duplicates). The admin “Menu Management” entry itself cannot be deleted or hidden, so admins can't lock themselves out. If the menu API is unreachable the default sidebar already in each page is used. |
| 11 | CRUD by record ID | All existing endpoints use numeric `{id}` path variables with ownership/role checks. New menu and audit endpoints follow the same pattern. |

### Upgrading an existing database (no data loss)

1. **Back up first** (MySQL): `mysqldump -u root -p lifetag_hms > backup.sql`
2. Deploy the new backend. With `spring.jpa.hibernate.ddl-auto=update` (the default) it creates the two new tables
   (`audit_log`, `menu_items`) by itself, **adds nothing else and drops nothing**, and seeds the default menu
   the first time `menu_items` is empty. Alternatively run `backend/sql/migration_001_audit_and_menu.sql`
   yourself (`CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE`; safe to run twice) and then start the app.
3. Set `JWT_SECRET` (the MySQL profile now refuses to start without it).
4. (The migration script also adds `users.tokens_valid_after_ms`; `ddl-auto=update` does this by itself too.) Replace the `frontend/` folder. Sign in as an administrator: **Audit Log** and **Menu Management** appear under Administration.
5. Existing rows that break the new validation limits (for example a phone number containing letters) are not
   changed; they are only rejected the next time someone edits that record.

### What was and was not tested

* **Tested (real browser, Chromium):** the frontend against a *mock* API that imitates the new endpoints:
  dynamic sidebar, menu add / edit / duplicate rejection / delete, invalid page-name rejection, audit table rendering,
  no horizontal overflow on every page at 1366 / 820 / 390 / 320 px, logout button sends the server logout request, no JavaScript errors on the new pages, redirect to login without a session,
  patient redirected away from an admin URL.
* **Not tested:** the Java backend was **not compiled or run** in the environment used to prepare this package
  (Maven Central was blocked there). The new Java code was syntax-checked with `javac` only. Please run
  `cd backend && mvn clean package` and `mvn spring-boot:run` first and report any compile error.
  Audit-log creation, the real menu API and the validation messages therefore still need to be verified on your machine
  (steps below).

### Quick verification checklist (on your machine)

1. Configure MySQL and the required environment variables using `backend/README.md`, then run `cd backend && mvn spring-boot:run`. Sign in using the administrator account you bootstrapped.
2. Add a patient → open **Audit Log** → an `INSERT` row for `patients` appears with your user ID; edit it → `UPDATE` row with old/new values; delete → `DELETE` row.
3. Filter the Audit Log by your user ID and by the patient's record ID.
4. **Menu Management** → add an entry, reload any admin page → it appears in the sidebar; hide / delete it → it disappears.
5. Sign out, then call the API with the old token → `401/403`. Failed logins show as `LOGIN_FAIL` in the Audit Log.
6. Try to POST an invalid value (e.g. patient age 500) → `400` with a message. Sign in as a Patient and call `GET /api/audit-logs` → `403`.

## Architecture at a glance

- **Auth**: JWT, issued by `POST /api/auth/login`, role embedded in the token (`ADMIN` /
  `DOCTOR` / `RECEPTION` / `PATIENT`), validated on every request by `JwtAuthFilter`.
- **Data**: JPA entities cover Patient, Doctor, Appointment, Billing, RoomBed, BloodBank,
  Insurance, Scheme, PatientScheme, Feedback, StaffActivity, User. Schema is created/kept
  in sync automatically (`ddl-auto=update`); no manual migration step needed for a fresh deploy.
- **API surface**: one REST controller per module under `/api/*` — see `backend/README.md`
  for the full endpoint table.
- **Frontend**: one HTML page per screen under `frontend/pages/`, sharing `css/` and `js/`.
  `js/api-config.js` is the single fetch()-based client for the backend (`LifetagAPI`).

## Status: what's fully wired vs. what's next

- ✅ **Deployment** — Docker images, docker-compose, nginx reverse proxy, env-driven config
  (DB, JWT secret, CORS).
- ✅ **Login** (`index.html` / `js/script.js`) — calls the real `/api/auth/login` endpoint
  and stores the JWT.
- ✅ **Core modules wired to the live backend** — Patients, Doctors, Appointments, Billing
  (with a JSON line-items column so multi-item invoices persist), Room/Bed management
  (assign/vacate via the real `/rooms-beds/{id}/assign` endpoint), Blood Bank (donors +
  stock + a new `blood_requests` table/endpoint added to support the Requests tab), Reports
  & Analytics (charts computed client-side from live data), the Admin Dashboard stat cards
  and Staff Activity log (every create/update/delete anywhere in the app now writes a real,
  server-persisted audit entry).
- ✅ **Demo data** — `DataSeeder` now seeds a small, realistic dataset across every module
  (3 patients, 3 doctors, rooms/beds, blood bank entries + requests, appointments, invoices)
  so the app isn't empty on first run.
- ✅ **Insurance, Government Schemes, Feedback, Doctor/Patient portals** — all wired to the
  live backend. Claim statuses are validated server-side; patients can download a real PDF
  claim summary and (for approved scheme claims) a sanction letter, generated by the backend.

## Known limitations

- **Government scheme submission is a simulation.** "Submit to government" records the claim
  with a clearly labelled SANDBOX reference; nothing is transmitted to any real government
  system. That requires formal NHA empanelment and production credentials, which is a
  business/legal process, not a code change. Sanction letters for such claims carry a notice
  saying they are not official.
- **MySQL requires `DB_PASSWORD`** — there is intentionally no default in
  `application-mysql.properties`, and docker-compose refuses to start without it.
- **The backend has not been compiled in the environment that produced this zip** (no access
  to Maven Central there). Run `mvn clean package` once on a machine with internet access.
