# shared

Client logic for Android, web and the dev CLI (Kotlin Multiplatform). No UI and no MLS implementation: the screens live in
`composeApp`, and `MlsClient` is implemented in `:mls` (ADR-0007).

Layers (`CLAUDE.md`, *Architecture rules*), each a package under `app.gifitup.shared`. Dependencies point inward only:

- `presentation`: plain state holders the screens render (ADR-0013); no androidx.
- `domain`: use cases. They import only the Kotlin standard library, `kotlinx.coroutines` and shared's own `domain` and
  `data` packages, and detekt's `ForbiddenImport` rule enforces it (`config/detekt/detekt.yml`).
- `data`: network (the Ktor client and the closed `ApiError` codes), local storage seams and the crypto facade. Nothing
  outside `data` touches these details.

Targets: Android and `wasmJs` for the apps, and `jvm()` for the dev CLI (`:cli`), the end-to-end test (`:e2e`) and the
fast host tests (`jvmTest`).
