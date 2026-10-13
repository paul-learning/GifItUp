# ADR-0001: Tech stack: Kotlin Multiplatform + Compose, Ktor backend

- **Status:** accepted
- **Date:** 2026-10-13

## Context
We need Android and Web clients from a single codebase. Development happens on Windows, so iOS is out of scope for now, though it should stay possible later. The backend runs on one Hetzner VM. It is a hobby project, so it should cost nothing beyond the VM.

## Decision
- **Clients:** Kotlin Multiplatform with **Compose Multiplatform** UI, targets `androidTarget` and `wasmJs` (browser).
- **Shared code:** `shared` KMP module for domain, data, networking (Ktor client), serialization (kotlinx.serialization) and the crypto facade.
- **Backend:** **Ktor** server on the JVM, **PostgreSQL**, migrations via Flyway (or similar, chosen at scaffold time). It shares wire models with the clients through a dedicated `protocol` KMP module (wire models only). The server never depends on `shared`, which is client code.
- **Deployment:** Docker Compose on the VM with **Caddy** as reverse proxy, which provides automatic TLS. Deployed by a GitHub Actions workflow.
- **Quality:** ktlint + detekt, kotlin.test, GitHub Actions CI as the merge gate.

## Consequences
- One language (Kotlin) on client and server, so the protocol models are shared.
- Kotlin/Wasm is newer than Kotlin/JS. If a blocking gap shows up (e.g. JS interop for the crypto WASM module), fall back to the `js` target and record it here.
- iOS can be added later by adding targets. This requires keeping `commonMain` clean.
