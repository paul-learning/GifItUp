# shared

Client logic for Android, web and the dev CLI (Kotlin Multiplatform). No UI and no MLS implementation: the screens live in
`composeApp`, and `MlsClient` is implemented in `:mls` (ADR-0007).

Layers (`CLAUDE.md`, *Architecture rules*), each a package under `app.gifitup.shared`. Dependencies point inward only:

- `presentation`: plain state holders the screens render (ADR-0013); no androidx. They call domain use cases and read
  data types (stores, `ApiError`).
- `domain`: use cases. They call `data` directly (`GifItUpApi`, the storage interfaces, `MlsClient`; no separate port
  interfaces, owner decision on #133, 2026-10-21) and import only the Kotlin standard library, `kotlinx.coroutines` and
  shared's own `domain` and `data` packages. detekt's `ForbiddenImport` rule enforces that for import lines
  (`config/detekt/detekt.yml`); a fully qualified name in code is not checked.
- `data`: network (the Ktor client and the closed `ApiError` codes), local storage seams and the crypto facade. Ktor,
  serialization and `protocol` wire models stay in here: domain and presentation see only data's own types.

Targets: Android and `wasmJs` for the apps, and `jvm()` for the dev CLI (`:cli`), the end-to-end test (`:e2e`) and the
fast host tests (`jvmTest`).
