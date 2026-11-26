# LifeTag HMS — Spring Boot Backend

A REST API backend for the LifeTag Hospital Management System frontend, built with
**Spring Boot 3.3 / Java 17 / Spring Data JPA / Spring Security (JWT)**.

## 1. Requirements

- Java 17+ (`java -version`)
- Maven 3.8+ (`mvn -version`) — or use an IDE (IntelliJ/Eclipse) that has Maven built in
- A running MySQL 8.0+ database (local install or hosted database). H2 has been removed.

> This project needs internet access the **first time** you build it, so Maven can download
> Spring Boot's dependencies from Maven Central. After that first build they're cached locally
> in `~/.m2`, and you can build offline.

## 2. Configure MySQL and run locally

Create a database and a user in MySQL (adjust the username/password to your own local setup):

```sql
CREATE DATABASE lifetag_hms;
CREATE USER 'lifetag_user'@'localhost' IDENTIFIED BY 'replace-with-a-strong-password';
GRANT ALL PRIVILEGES ON lifetag_hms.* TO 'lifetag_user'@'localhost';
```

Set the required environment variables before starting the backend. In Windows PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE = "mysql"
$env:DB_HOST = "localhost"
$env:DB_PORT = "3306"
$env:DB_NAME = "lifetag_hms"
$env:DB_USERNAME = "lifetag_user"
$env:DB_PASSWORD = "your-local-mysql-password"
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$env:JWT_SECRET = [Convert]::ToBase64String($bytes)
$rng.Dispose()
$env:BOOTSTRAP_ADMIN_USERNAME = "admin"
$env:BOOTSTRAP_ADMIN_PASSWORD = "Choose-A-Strong-Password-Here"
$env:BOOTSTRAP_ADMIN_EMAIL = "admin@example.com"
cd backend
mvn spring-boot:run
```

The API starts on **http://localhost:8080**. The app does not create demo users by default. On an
empty database, the `BOOTSTRAP_ADMIN_*` variables create the first administrator. Use your own
unique values and remove `BOOTSTRAP_ADMIN_PASSWORD` from the environment after the first startup.
Keep these variables in your local environment or your host's secret settings, never in Git.

## 3. Database configuration

`src/main/resources/application-mysql.properties` contains the MySQL connection settings. The
application defaults to the `mysql` profile. Set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD`, and a cryptographically random `JWT_SECRET` (at least 32 bytes). `SEED_DEMO_DATA`
and `SEED_LARGE_DATASET` both default to `false` and should remain disabled for any database that
contains real or valuable data.

## 4. Demo logins (disposable demo database only)

See `CREDENTIALS.md` for published demo usernames and passwords. To seed them, explicitly set
`SEED_DEMO_DATA=true` before starting the app against a disposable, empty MySQL database. Never
enable this on a production or real-data database. For normal use, create the first administrator
using the `BOOTSTRAP_ADMIN_*` environment variables described above.

## 5. Connecting the existing frontend

Open any page and include, before the page's own script:

```html
<script src="../js/api-config.js"></script>
```

This exposes a `LifetagAPI` object with helper methods (`LifetagAPI.login(...)`,
`LifetagAPI.getPatients()`, `LifetagAPI.createAppointment(...)`, etc.) that call this backend.
It defaults to `http://localhost:8080/api` — change `API_BASE_URL` at the top of
`js/api-config.js` if you deploy the backend elsewhere. The existing pages currently read/write
mock data from `localStorage`; swapping those calls for the matching `LifetagAPI` method is the
remaining step to make each page fully live (the auth, CRUD and reporting endpoints below are
all ready to use).

## 6. API overview

All endpoints are under `/api`. Endpoints other than `/api/auth/**` require a JWT:

```
Authorization: Bearer <token from /api/auth/login>
```

| Module            | Base path              | Notes                                   |
|-------------------|-------------------------|------------------------------------------|
| Auth              | `/api/auth`             | `POST /login`, `POST /register`, `GET /me` |
| Patients          | `/api/patients`         | full CRUD                               |
| Doctors           | `/api/doctors`          | full CRUD                               |
| Appointments      | `/api/appointments`     | CRUD + `/patient/{id}`, `/doctor/{id}`  |
| Billing           | `/api/billing`          | CRUD + `/patient/{id}`                  |
| Rooms & Beds       | `/api/rooms-beds`       | CRUD + `PATCH /{id}/assign`             |
| Blood Bank        | `/api/blood-bank`       | full CRUD                               |
| Insurance         | `/api/insurance`        | CRUD + `/patient/{id}`                  |
| Schemes           | `/api/schemes`          | full CRUD                               |
| Patient Schemes   | `/api/patient-schemes`  | enroll/list per patient                 |
| Feedback          | `/api/feedback`         | create/list                             |
| Staff Activity    | `/api/staff-activity`   | create/list                             |
| Reports           | `/api/reports/summary`  | dashboard counters                      |

### Audit log and menu endpoints (added)

| Method | Path | Who | Purpose |
|--------|------|-----|---------|
| GET | `/api/audit-logs?userId=&recordId=&table=&operation=&page=&size=` | Admin | Search the audit trail |
| GET | `/api/audit-logs/{id}` | Admin | One audit entry |
| GET | `/api/menu` | Any signed-in user | The caller's own sidebar (active items for their role) |
| GET/POST | `/api/admin/menu` | Admin | List / create menu items |
| GET/PUT/DELETE | `/api/admin/menu/{id}` | Admin | Read / update / delete one menu item |

## 7. Project layout

```
backend/
  pom.xml
  src/main/java/com/lifetag/hms/
    entity/        JPA entities (Patient, Doctor, Appointment, Billing, RoomBed,
                    BloodBank, Insurance, Scheme, PatientScheme, Feedback, StaffActivity, User)
    repository/     Spring Data JPA repositories
    controller/     REST controllers (one per module, see table above)
    security/       JWT generation/validation + Spring Security wiring
    config/         SecurityConfig (CORS, JWT filter chain) + DataSeeder (demo data)
    dto/            Request/response payloads for auth
    exception/      Global JSON error handling
  src/main/resources/
    application.properties        common config (active profile, JWT secret, CORS origins)
    application-mysql.properties  MySQL database configuration
```

## 8. Docker

A production Dockerfile and a full docker-compose stack (MySQL + backend + frontend) are
included one level up — see `../deploy/docker-compose.yml` and `../docs/deployment-guide.md`.

## 9. Build the MySQL database yourself (standalone SQL script)

If you'd rather create and own the database directly instead of letting Hibernate
auto-generate the schema on app startup, run:

```bash
mysql -u root -p < backend/sql/lifetag_hms_full.sql
```

This single script creates the `lifetag_hms` database, all 13 tables (with proper foreign
keys and indexes), and seeds the same demo accounts/records as `DataSeeder.java` — so
you get an equivalent database either way.

After running it, point the app at that database the normal way:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

Since the schema already exists, consider setting `spring.jpa.hibernate.ddl-auto=validate`
in `application-mysql.properties` so the app checks the schema matches instead of trying
to alter it (the default `update` is harmless here too — it only adds what's missing).

## 10. A note on "zero errors"

This backend was written and reviewed by hand against Spring Boot 3.3 / Spring Security 6 /
jjwt 0.12 APIs, but it could not be compiled inside the sandboxed environment that produced this
zip: that environment blocks outbound access to Maven Central, so `mvn` cannot download
Spring Boot's dependencies there. Please run `mvn clean package` (or `mvn spring-boot:run`) the
first time on a machine with normal internet access — if anything doesn't compile, send me the
error and I'll fix it immediately.
