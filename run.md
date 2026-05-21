# IssueFlow — Setup & Run Guide

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21 or 25 |
| Docker | 24+ (with Compose V2) |

No separate Maven installation is required — use the included `mvnw` wrapper (`mvnw` on macOS/Linux, `mvnw.cmd` on Windows).

Verify Java is on `PATH`:

```bash
java -version
```

The major version reported must be `21` or `25`.

---

## 1. Start the Database

### Option A — Docker (recommended)

If Docker Desktop is installed, Spring Boot starts the container automatically on first run. You can also start it manually:

```bash
docker compose up -d
```

### Option B — Local PostgreSQL (no Docker)

If Docker is not available, disable the auto-start integration and point the app at a local PostgreSQL instance:

1. Create the database:
   ```sql
   CREATE DATABASE issueflow;
   ```
2. Set environment variables before running (see step 4):
   ```bash
   SPRING_DOCKER_COMPOSE_ENABLED=false
   DB_USERNAME=<your_pg_user>
   DB_PASSWORD=<your_pg_password>
   DB_NAME=issueflow
   ```

Default connection details (all overridable via environment variables):

| Variable | Default |
|----------|---------|
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `issueflow` |
| `DB_USERNAME` | `issueflow` |
| `DB_PASSWORD` | `issueflow` |
| `SPRING_DOCKER_COMPOSE_ENABLED` | `true` |

---

## 2. Schema Migrations

No manual migration step is required. Hibernate manages the schema automatically (`ddl-auto: update`) on startup.

---

## 3. Bootstrap Admin User

Every endpoint except `POST /auth/login` requires a valid JWT, so the application seeds a default admin user the first time it starts against an empty `users` table.

| Variable | Default |
|----------|---------|
| `BOOTSTRAP_ADMIN_USERNAME` | `admin` |
| `BOOTSTRAP_ADMIN_PASSWORD` | `admin` |
| `BOOTSTRAP_ADMIN_EMAIL` | `admin@issueflow.local` |

The seed only runs when the table is empty — subsequent restarts are no-ops. **Change `BOOTSTRAP_ADMIN_PASSWORD` before any non-local deployment.**

---

## 4. Build

macOS / Linux / Git Bash:

```bash
./mvnw clean package -DskipTests
```

Windows PowerShell / cmd:

```powershell
.\mvnw.cmd clean package -DskipTests
```

---

## 5. Run

macOS / Linux:

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

Without Docker (Option B), set env vars first:

```bash
# macOS / Linux
export SPRING_DOCKER_COMPOSE_ENABLED=false
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
./mvnw spring-boot:run
```

```powershell
# Windows PowerShell
$env:SPRING_DOCKER_COMPOSE_ENABLED="false"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="postgres"
.\mvnw.cmd spring-boot:run
```

Or run the packaged JAR directly:

```bash
java -jar target/issueflow-*.jar
```

The API is available at `http://localhost:8080` (override with `SERVER_PORT`).

> **JWT secret:** The default development secret is set in `application.yaml`. Override it in production with `JWT_SECRET` (minimum 32 characters).

---

## 6. Run Tests

Tests use an embedded H2 database — no Docker or running application is required.

```bash
./mvnw test           # macOS / Linux
.\mvnw.cmd test       # Windows
```

---

## 7. Smoke Test

After the app is running, verify the auth flow end-to-end.

### macOS / Linux (curl)

```bash
# Log in with the bootstrap admin and capture the token
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

# Use it on an authenticated endpoint
curl -s http://localhost:8080/auth/me -H "Authorization: Bearer $TOKEN"
```

### Windows PowerShell

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/auth/login `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"admin"}'

$token = $login.accessToken

Invoke-RestMethod -Method Get -Uri http://localhost:8080/auth/me `
  -Headers @{ Authorization = "Bearer $token" }
```

A successful `GET /auth/me` confirms the JWT issuance, validation, and security chain are all working.

---

## API Notes

### `POST /users` requires a `password` field

The README's request-body example for `POST /users` is `{ username, email, fullName, role }` — it omits the `password` field for brevity. The implementation requires `password` (min 6 characters) because every account must be able to authenticate via `POST /auth/login`; without a hashed password the user is permanently locked out. The `password` field is hashed with BCrypt before being stored and is never returned in any response.

### Mentions pagination (`GET /users/{userId}/mentions`)

| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | `1` | 1-indexed page number |
| `pageSize` | `20` | Results per page |

Response envelope: `{ "data": [...], "total": <total records>, "page": <current page> }`
