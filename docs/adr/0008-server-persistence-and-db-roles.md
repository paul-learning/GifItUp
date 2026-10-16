# ADR-0008: Server persistence and database roles

- **Status:** accepted
- **Date:** 2026-10-16
- **Issue/PR:** #12 (PR B). Round-0 plan review on #68 (SEC-4, SEC-5, SEC-10, SEC-13); M0 security finding SEC-M0-6; M0 architecture finding ARCH-M0-10 (`/health` readiness).

## Context
#12 adds the first server-side data: accounts, devices and sessions. Later issues add invites (#13), KeyPackages (#14) and the delivery service (#21). The decisions needed:
- how the server talks to PostgreSQL;
- how the schema evolves;
- which role the server connects as.

Constraints:
- **The server is untrusted** (CLAUDE.md), but must not be easy to turn into a foothold either. Postgres runs as the image's superuser `gifitup` (M0 setup), and SEC-M0-6 found that an SQL injection as superuser means `COPY … TO PROGRAM`, which runs code in the database container.
- **Deploys roll back automatically** (`deploy.sh`), but a schema doesn't.
- **Supply chain:** every dependency must be justified.
- **No new infrastructure.**

## Options considered
**Data access** (owner decision, 2026-10-16):
1. **Plain JDBC + HikariCP** (chosen): prepared statements behind small repository classes. Every query is visible in review. Two small, mature dependencies.
2. **Exposed**: a typed DSL with less boilerplate, but it is a large dependency and puts a layer between the reviewer and the SQL.
3. **jOOQ**: a code generator in the build. Too heavy for a handful of tables.

**Migrations:** Flyway (chosen; versioned SQL files, checksums, the de facto standard) or Liquibase (XML/YAML changelogs, heavier). A hand-written runner was rejected: a history table with checksums and locking is exactly what shouldn't be reinvented.

**Who runs migrations** (owner decision, 2026-10-16):
1. **One-shot `migrate` container** (chosen). The same image with a different main class. It is the only container that gets the superuser password, and it exits after migrating.
2. **The server migrates at startup.** Simpler, but then the long-running, internet-facing process holds credentials that can change the schema.

## Decision

### Roles
| Role | Login | Owns / may | Used by |
|---|---|---|---|
| `gifitup` | yes (superuser, from the image) | everything | the `migrate` container only |
| `gifitup_owner` | **NOLOGIN** | owns the schemas `gifitup` and `flyway` and every table | Flyway: its superuser connections set `role=gifitup_owner` as a startup option |
| `gifitup_app` | yes | `CONNECT` on the database; `USAGE` on `gifitup`; `SELECT/INSERT/UPDATE/DELETE` on its tables (also future ones, via default privileges); nothing on `flyway` | the server |

- **Attributes are pinned on every migration run:** `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`. The app role is also `NOINHERIT` and has a connection limit of 20. Every role membership of either role is revoked, per grantor, because a hand-made `GRANT gifitup_owner TO gifitup_app` would allow `SET ROLE`, which `NOINHERIT` doesn't stop (#69 CR-1). So a role changed by hand goes back to least privilege on the next deploy; a test grants the owner role, `pg_execute_server_program` and `CREATEDB` by hand and checks that the next run removes them.
- **`PUBLIC` privileges:**
  - `PUBLIC` loses all privileges (`CONNECT`, `TEMP` and `CREATE`) on **every** connectable database of the cluster, including `postgres` and `template1`, so the app role can't reach them (#69 SEC-19). The app role gets `CONNECT` on `gifitup` only.
  - `CREATE` on `public` is revoked explicitly, although PostgreSQL 15+ already does that by default.
- **What the app role can't do** (one test each, SQLSTATE `42501`): create or alter tables, create schemas or temp tables, `TRUNCATE`, read or write `flyway.flyway_schema_history`, `SET ROLE gifitup_owner`, `COPY … TO PROGRAM`, or change roles. An SQL injection in the server can still read and change the app's rows. It can't run programs, change the schema, or tamper with the migration history.
- **The owner role has no password at all.** Nothing can log in as it.

### Secrets
- **Two Docker secret files on the VM:**
  - `secrets/postgres_password`: the superuser's, as before
  - `secrets/postgres_app_password`: new
- **Who gets which:**
  - The server container mounts only the app password.
  - `migrate` mounts both: it sets the app role's password from the file, so rotating it means editing the file and redeploying.
- **The app password is set as a SCRAM verifier, computed client-side** (pgjdbc `alterUserPassword`). The cleartext never appears in an SQL statement, so it can't end up in a Postgres error log, and quoting can't break or inject. It is set **after** Flyway succeeds, so a failed migration leaves the running server's credentials alone, even right after a password rotation (#69 CR-4).
- **Reading the files:** both are read from `*_FILE` paths and must be one line of printable ASCII and at least 24 characters. Error messages name the variable, never the content.
- **File modes:** compose without swarm ignores `uid`/`gid`/`mode` on file secrets, so the host file mode decides. Both files are `0444` inside the `0700` `secrets/` directory (`docs/ops/vm-setup.md`). Before it does anything, `deploy.sh` checks that the directory is `0700`, owned by the deploying user and not a symlink, and that both files are regular files (not symlinks), non-empty and world-readable (#69 SEC-17).

### Driver settings
On every connection, server and migration alike:
- **`requireAuth=scram-sha-256`** (#69 SEC-15): any container on the shared `proxy` network can register the alias `postgres`, and on Docker it can win the lookup over the internal network. Without this setting, pgjdbc sends the password in cleartext or as MD5 if the server asks. With it, the driver refuses every other method (tests: fake servers with `password`, `md5` and `trust` auth are refused, and connect without the setting). A fake server can't complete SCRAM, because it lacks the stored verifier and pgjdbc checks the server's proof. It can't relay to the real database either, which is only on the internal `backend` network. What remains is denial of service: the server can't connect, `/health` says 503. A fixed IP for `postgres` would close that too, but it means recreating the network on the VM; not done.
- **`logServerErrorDetail=false`:** a constraint violation's `DETAIL` (`Key (username)=(alice) already exists`) stays out of exception messages, so nothing that logs an exception leaks a username.
- **JDBC URL:** `DB_URL` must match `jdbc:postgresql://host[:port]/database` with **no query parameters**, so a URL can't switch a driver setting back.
- **Server-side logging:** Postgres runs with `log_error_verbosity=terse`, so its own container log has no `DETAIL` lines with failing rows (#69 SEC-16; tested by reading the test container's log). Statements use bind parameters, and parameters aren't logged on error (`log_parameter_max_length_on_error` stays 0).
- **Logging:** the server logs SQL errors only by class (`StatusPages`, #68). Repositories (PR D) use `INSERT … ON CONFLICT DO NOTHING RETURNING` for expected conflicts, so no exception is raised at all.

### Schema and migrations
- **Location:** the app's tables live in the schema `gifitup`, Flyway's history in `flyway` (`defaultSchema`). Migration SQL qualifies every table with `gifitup.`; the server's pool sets `gifitup` as each connection's schema.
- **Flyway settings:** `cleanDisabled`, `validateMigrationNaming`, no `baselineOnMigrate`, no `outOfOrder`.
- **Expand-only rule:** a migration must keep the **previous** release working, because `deploy.sh` rolls back the image but not the schema. Allowed: add tables or nullable columns, add constraints that existing data and the previous release already satisfy. A destructive change (drop or rename a column, add a NOT NULL without a default) goes in a later release, once nothing uses the old shape. Reviewers check this for every migration.
- **V1:** `accounts`, `devices`, `sessions`.
  - The username CHECK is the crate's rule byte for byte (ADR-0007).
  - The device signature key is exactly 32 bytes and unique.
  - Only the 32-byte SHA-256 of a session token is stored.
  - `ON DELETE CASCADE` runs from account to device to session.

### Deploy flow
`deploy.sh` stage 2 checks the secret files, then runs `docker compose --profile migrate run --rm migrate` with the **new** image, before it touches `.env` or the running server. If the migration fails, the deploy is rejected and the old server keeps running. After a migration, a failed health check rolls back the image as before. The expand-only rule is what makes that safe.

### Readiness
`/health` answers `503 {"status":"unavailable",…}` when the pool can't hand out a connection within 2 s or the connection doesn't validate within 1 s, so one check takes at most about 3 s. A pool saturated by real traffic also reads as unavailable for the next 5 s; that only matters during a deploy's health gate. The result is cached for 5 s, and concurrent requests share one check, so public `/health` traffic costs at most one database ping per 5 s (`CachedReadiness`). `deploy.sh`'s health gate therefore also covers the database. The server also fails at startup if the database is unreachable or the credentials are wrong, and Docker restarts it.

### Dependencies
| Artifact | Version | Why | Notes |
|---|---|---|---|
| `org.postgresql:postgresql` | 42.7.14 | JDBC driver | The reference driver |
| `com.zaxxer:HikariCP` | 7.1.0 | Connection pool | Only depends on `slf4j-api` |
| `org.flywaydb:flyway-core`, `flyway-database-postgresql` | 13.10.3 | Migrations | Lombok (listed at compile scope in Flyway's POM) and `flyway-database-cockroachdb` are excluded; the migration tests show Flyway works without them |
| `org.testcontainers:testcontainers-postgresql` | 2.0.5 | Real PostgreSQL in tests | Test classpath only |

**Testcontainers in detail:**
- The tests run the **exact image compose pins**: they read tag and digest from `deploy/docker-compose.yml`, so the two can't drift.
- **Ryuk** (Testcontainers' reaper container, pulled by tag, with the Docker socket mounted) is **disabled** (`TESTCONTAINERS_RYUK_DISABLED` in `server/build.gradle.kts`). The tests stop their container with a shutdown hook instead. If a test JVM is killed hard, a container is left behind, which is acceptable on a dev machine and irrelevant on CI's throwaway runner.
- The tests need a running Docker daemon: Docker Desktop locally, preinstalled on `ubuntu-latest`.

## Consequences
- **SEC-M0-6 is closed for the server:**
  - An SQL injection gets row access as `gifitup_app`, not superuser.
  - The superuser password lives only in a container that runs for seconds per deploy.
  - The superuser can still do everything: whoever holds `secrets/postgres_password` or the Docker daemon on the VM owns the database (threat model, "Shared VM").
- **Before the first deploy with this change, the owner creates `secrets/postgres_app_password`** (`docs/ops/vm-setup.md`). Without it, `deploy.sh` rejects the deploy before touching anything.
- **Every migration is reviewed against the expand-only rule.** A destructive one needs a two-release plan.
- **Repositories:** they arrive with the auth endpoints (PR D of #12). They use prepared statements only, through `Database.transaction`.
- **Metadata:** the database holds usernames, device public keys, session hashes and timestamps, as the threat model already lists. Nothing new becomes server-visible here.
