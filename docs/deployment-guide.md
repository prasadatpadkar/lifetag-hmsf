# Deployment Guide

## Architecture

```
                    ┌──────────────┐
   Internet ──HTTP──▶  nginx (80)  │  frontend container — static HTML/CSS/JS
                    └──────┬───────┘
                           │ /api/*  (reverse proxy, same-origin)
                           ▼
                  ┌──────────────────┐
                  │  Spring Boot API  │  backend container, port 8080
                  └────────┬─────────┘
                           │ JDBC
                           ▼
                  ┌──────────────────┐
                  │    MySQL 8.0      │
                  └──────────────────┘
```

The frontend container never talks to the backend cross-origin — nginx proxies `/api/*`
straight to the `backend` service on the Docker network, so the browser only ever sees one
origin and CORS doesn't come into play in production.

## Quick start (single host, Docker Compose)

```bash
cd deploy
cp .env.example .env
# edit .env - set ALL of these (compose refuses to start without the required ones):
#   JWT_SECRET (openssl rand -hex 32), DB_ROOT_PASSWORD, DB_PASSWORD,
#   BOOTSTRAP_ADMIN_USERNAME / _PASSWORD / _EMAIL  (your first administrator)
docker compose up -d --build
docker compose logs -f backend   # confirm "Started HmsBackendApplication" and "Created first administrator"
```

Then open **http://localhost** and sign in with the bootstrap admin you chose. **There are no
built-in or demo logins in this mode.** After the first sign-in, delete
`BOOTSTRAP_ADMIN_PASSWORD` from `.env` (it is only read while the users table is empty) and create
receptionists, doctors and other admins from *Staff Management* / *Doctor Management*.

The backend refuses to start if `JWT_SECRET` is the built-in development default or shorter than
32 characters, and refuses MySQL without `DB_PASSWORD`.

To rebuild after a code change: `docker compose up -d --build backend` (or `frontend`).

## How the pieces fit together

- **`backend/Dockerfile`** — multi-stage Maven build → a small `eclipse-temurin:17-jre-alpine`
  runtime image. Reads `SPRING_PROFILES_ACTIVE=mysql` plus `DB_HOST`, `DB_PORT`, `DB_NAME`,
  `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ALLOWED_ORIGINS` from the environment
  (set in `deploy/docker-compose.yml`).
- **`frontend/Dockerfile`** — no build step needed (plain HTML/CSS/JS): copies the static
  site into an `nginx:alpine` image alongside `frontend/nginx.conf`.
- **Schema** — `spring.jpa.hibernate.ddl-auto=update` creates/updates the MySQL schema
  automatically on startup. On an empty database `DataSeeder` creates only the bootstrap
  admin (demo data is off unless `SEED_DEMO_DATA=true`). Do **not** import
  `backend/sql/lifetag_hms_full.sql` - it is a demo-only, out-of-date script.
- **`deploy/docker-compose.yml`** — wires up `mysql` → `backend` → `frontend`, with a health
  check gating the backend's start on MySQL being ready.

## Production checklist

- [ ] `JWT_SECRET` is a real random 32+ byte value from a secrets manager — never committed
- [ ] `DB_PASSWORD` / `DB_ROOT_PASSWORD` are strong, unique, and not the compose defaults
- [ ] `ALLOWED_ORIGINS` is restricted to your real domain(s) if anything calls the API cross-origin
- [ ] **TLS is terminated in front of the stack** (Caddy/Traefik/nginx with a Let's Encrypt cert, a cloud load balancer, or Cloudflare). The bundled nginx serves plain HTTP on port 80 - logins and patient data must not travel over the internet unencrypted
- [ ] Consider switching `spring.jpa.hibernate.ddl-auto` to `validate` and introducing versioned
      migrations (e.g. Flyway) once the schema stabilizes, instead of relying on Hibernate auto-DDL
- [ ] The MySQL data volume (`mysql_data`) is on durable storage with automated backups
- [ ] `BOOTSTRAP_ADMIN_PASSWORD` is removed from `.env` after the first sign-in, and the admin password changed to one only you know
- [ ] `SEED_DEMO_DATA` is not set to `true`
- [ ] The MySQL port is not exposed publicly (compose no longer publishes it; the backend port is bound to 127.0.0.1)
- [ ] Container logs (stdout/stderr) are shipped somewhere durable

## Deploying to a cloud platform

- **Database**: use a managed MySQL 8.0 instance (RDS, Cloud SQL, Azure Database for MySQL)
  instead of the `mysql` container; point the backend's `DB_HOST`/`DB_PORT` at it.
- **Backend**: deploy the `backend` image to any container platform (ECS, Cloud Run, App
  Service, or a VM running Docker). Set the same environment variables listed above.
- **Frontend**: the `frontend` image is just static files behind nginx — any static host
  (S3+CloudFront, Netlify, Vercel) or the same container platform works, as long as `/api/*`
  is routed to the backend (via the included `nginx.conf` or your platform's routing rules).

## Backup / restore

```bash
# Backup
docker exec lifetag-mysql mysqldump -u root -p lifetag_hms > backup-$(date +%F).sql

# Restore
docker exec -i lifetag-mysql mysql -u root -p lifetag_hms < backup-2026-08-26.sql
```

## Scaling notes

- The backend is stateless (JWT, no server-side sessions) — safe to run multiple replicas
  behind a load balancer.
- MySQL is the only stateful component; vertical scaling covers most single-hospital
  deployments.

## Login protection and backups

- Login and change-password attempts are throttled in memory: 5 failures for the same
  username from the same address locks that combination for 15 minutes (per backend
  instance; use a shared store like Redis if you run several replicas).
- Password rules: minimum 8 characters. Accounts created by staff get a random 12-character
  temporary password, shown once.
- Back up the `mysql_data` volume regularly (see *Backup / restore*) - patient records live only there.

## Known limitation: government scheme submission is simulated

"Submit to government" records a claim with a clearly labelled SANDBOX reference; nothing is sent
to any real government system. Real submission needs formal empanelment with the National Health
Authority and production credentials, which is a business/legal process. Sanction letters for
simulated claims say so on the page.

## Production safety checklist (read before deploying)

- MySQL is the only supported database; the application defaults to the `mysql` profile. Set `SPRING_PROFILES_ACTIVE=mysql` explicitly in hosting anyway.
- Set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD` in your hosting provider's environment settings. Do not commit real values.
- Set `JWT_SECRET` to a cryptographically random secret of at least 32 bytes. The MySQL profile intentionally has no fallback secret; the app should fail to start if it is missing.
- Keep `SEED_DEMO_DATA=false` and `SEED_LARGE_DATASET=false` for any database that contains real or valuable data. Public demo credentials are only for local development.
- Do not run `backend/sql/lifetag_hms_full.sql` against a database you need to preserve; it contains destructive database statements and demo data. Use the reviewed migration scripts for the intended schema changes. Back up your database before applying migrations.
- Set `ALLOWED_ORIGINS` to your actual frontend origin(s). Do not use a wildcard for a production hospital system.

