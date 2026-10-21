# ADR-0004: Dependency injection: manual constructor injection

- **Status:** accepted
- **Date:** 2026-10-14
- **Issue/PR:** #1

## Context
CLAUDE.md requires constructor injection and no global mutable state. We need to decide how object graphs are wired in the clients (`shared`, `composeApp`, `androidApp`) and in `server`. Constraints:
- Must work in `commonMain` for Android and Kotlin/Wasm, and later iOS.
- Every dependency is a supply-chain risk (public repo, E2EE app), so new libraries need a clear benefit.
- The app is small: a handful of repositories, use cases and ViewModels per platform.
- The crypto facade (`MlsClient`) gets a different implementation per platform (uniffi on Android, wasm-bindgen on Web). Wiring must make that swap explicit.

## Options considered
1. **Manual constructor injection with composition roots.** Each class takes its dependencies as constructor parameters. One plain-Kotlin "app graph" per entry point (`MainActivity`, wasm `main()`, server `Application.module()`) builds the objects. Pros: no dependency, no reflection, compile-time safety, works on every target, easy to read and test. Cons: the wiring code grows by hand. Scoping (per screen, per user session) has to be written explicitly.
2. **Koin.** Popular KMP DI library with Compose and Ktor integration. Pros: less wiring code, built-in ViewModel scoping. Cons: an extra runtime dependency. Errors appear at runtime, not compile time, unless the compile-time plugin is used. A service locator (`get()`, `koinInject()`) makes it easy to pull in dependencies implicitly, which works against our explicit layering.
3. **Compile-time DI (kotlin-inject, Metro).** Pros: graph validated at compile time. Cons: needs KSP or a compiler plugin across all targets, which adds build complexity on wasm. Not worth it at this size.

## Decision
Use **manual constructor injection** (option 1).
- Classes receive dependencies through constructors only. No service locator and no `object` singletons holding state.
- Each entry point has one composition root that builds the graph: `androidApp`, the wasm `main()` in `composeApp`, and `server`'s `Application.module()`.
- Platform-specific implementations (e.g. the `MlsClient` actual) are created in the platform's composition root and passed down as interfaces (see *Amendments*: they live in `data`, not `domain`).

## Consequences
- No new dependency. The wiring is plain Kotlin that the reviewers can read.
- Composition roots will grow. If wiring becomes painful (rough threshold: the graph no longer fits comfortably in one file per entry point, or session scoping gets error-prone), revisit with a new ADR. Koin is the likely candidate.
- ViewModels need a small factory per screen, built from the composition root (where they live: see *Amendments*).
- No metadata or security impact.

## Amendments
- **2026-10-21 (#57, ARCH-M0-5 of the M0 architecture review):** where the presentation layer lives was left open here and is decided in ADR-0013 (#17).
  - **State holders** are plain Kotlin classes in `:shared` (`app.gifitup.shared.presentation`) that take a `CoroutineScope`, so they run and are tested on the JVM without androidx.
  - **androidx `ViewModel`** wrappers and `navigation-compose` live in `composeApp` only. They are built from the composition root (`AppGraph` and the platform entry points) and hand the state holder its scope.
  - **Screens** render state and send events; they hold no logic (`CLAUDE.md`, *Periodic reviews*).
  - **Interfaces from data:** the platform interfaces (`MlsClient`, the storage seams) are declared in `:shared`'s `data` package, and use cases in `domain` call `data` directly (owner decision on #133, 2026-10-21; `shared/README.md`).
