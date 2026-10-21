# ADR-0001: Tech stack: Kotlin Multiplatform + Compose, Ktor backend

- **Status:** accepted
- **Date:** 2026-10-13

## Context
We need Android and Web clients from a single codebase. Development happens on Windows, so iOS is out of scope for now, though it should stay possible later. The backend runs on one Hetzner VM. It is a hobby project, so it should cost nothing beyond the VM.

## Decision
- **Clients:** Kotlin Multiplatform with **Compose Multiplatform** UI, targets Android and `wasmJs` (browser). Since AGP 9 the Android side is a KMP library plus the `:androidApp` shell (see *Amendments*).
- **Shared code:** `shared` KMP module for domain, data, networking (Ktor client), serialization (kotlinx.serialization) and the crypto facade.
- **Backend:** **Ktor** server on the JVM, **PostgreSQL**, migrations via Flyway (chosen in ADR-0008, not at scaffold time; see *Amendments*). It shares wire models with the clients through a dedicated `protocol` KMP module (wire models only). The server never depends on `shared`, which is client code.
- **Deployment:** Docker Compose on the VM with **Caddy** as reverse proxy, which provides automatic TLS. Deployed by a GitHub Actions workflow.
- **Quality:** ktlint + detekt, kotlin.test, GitHub Actions CI as the merge gate.

## Consequences
- One language (Kotlin) on client and server, so the protocol models are shared.
- Kotlin/Wasm is newer than Kotlin/JS. If a blocking gap shows up (e.g. JS interop for the crypto WASM module), fall back to the `js` target and record it here.
- iOS can be added later by adding targets. This requires keeping `commonMain` clean.

## Amendments
- **2026-10-21 (#58, ARCH-M0-9 of the M0 architecture review):** recorded what changed after this ADR was written.
  - **Migration tool:** not chosen at scaffold time. #12 chose Flyway, with the role model and who runs migrations, in ADR-0008.
  - **Android shell module:** AGP 9 keeps the APK out of Kotlin Multiplatform modules. So `composeApp` is a KMP *library* (the `android {}` target of the Android KMP library plugin, not `androidTarget`), and a thin `com.android.application` module, `:androidApp`, holds the Android entry points and the manifest and builds the APK.
  - **Modules added since:** `:mls` (the `MlsClient` implementation over the crate, ADR-0007), `:cli` and `:e2e` (dev CLI and end-to-end test, #79; never shipped). `CLAUDE.md`'s stack table lists them all.
