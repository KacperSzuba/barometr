---
name: gradle-build
description: Gradle build conventions for barometr — the version catalog as the only place versions live, convention plugins in build-logic, api versus implementation, toolchain, configuration-cache-safe task authoring, jOOQ codegen wiring, and what to do when adding a module or a dependency. Use when editing any build.gradle.kts, settings.gradle.kts or build-logic file, adding a dependency or module, or debugging a build, codegen or configuration-cache failure.
---

# Gradle build

**Apply when** touching any `*.gradle.kts`, the version catalog, or `build-logic`.

## Rules

1. **Every version lives in [gradle/libs.versions.toml](gradle/libs.versions.toml).**
   No inline `"group:artifact:1.2.3"` anywhere, including `build-logic`, which reaches
   the catalog through its own `dependencyResolutionManagement` block.
2. **A new dependency carries a comment saying why the framework does not cover it.**
   The existing entries model this — Resilience4j and crawler-commons each state that
   Spring ships no equivalent. If the comment is hard to write, re-read `library-first`.
3. **Verify a version exists before writing it.** Never infer a version number from
   memory; check the repository.
4. **Shared configuration goes into a convention plugin**, never copied between
   modules. The four that exist:
   [barometr.kotlin-base](build-logic/src/main/kotlin/barometr.kotlin-base.gradle.kts)
   (toolchain, compiler args, test logging),
   [barometr.spring-platform](build-logic/src/main/kotlin/barometr.spring-platform.gradle.kts)
   (BOMs, kotlin-spring, test deps),
   [barometr.module](build-logic/src/main/kotlin/barometr.module.gradle.kts) (every
   library module) and
   [barometr.jooq-codegen](build-logic/src/main/kotlin/barometr.jooq-codegen.gradle.kts)
   (the modules that own a schema). A third module needing the same three lines means
   a fifth plugin, not a third copy.
5. **`build-logic` is an included build, not `buildSrc`** — a change to a convention
   plugin then rebuilds only what depends on it, instead of invalidating everything.
6. **`api` versus `implementation` is decided by signatures.** A type that appears in
   a public signature is `api`; everything else is `implementation`. `platform-http`
   exposes `RestClient.Builder`, so its starter is `api`; that is the whole test.
7. **BOMs are imported as Gradle platforms**, not via
   `io.spring.dependency-management`. One fewer plugin, alignment handled by Gradle.
8. **Toolchain 21 everywhere**, including `build-logic` — without
   `kotlin { jvmToolchain(21) }` there, Kotlin and javac silently target different
   versions.
9. **Tasks must be configuration-cache safe.** Capture plain values at configuration
   time; never touch `project` inside a task action. Shared expensive resources are
   `BuildService`s —
   [MigratedPostgresService](build-logic/src/main/kotlin/pl/barometr/build/MigratedPostgresService.kt)
   starts one Postgres per build for all codegen.
10. **Codegen is generated from migrated migrations, never hand-written or checked
    in.** After changing the schema, regenerate — see `database-schema`.
11. **A module earns its existence.** It owns a schema, or code, or both — and a
    context is one module, not an `-api`/`-impl` pair. Twenty projects became nine
    when that rule was applied (review D20).
12. **JVM memory settings in `gradle.properties` are load-bearing** — the defaults
    fail as `Could not read class .../Row12.class` in an unrelated module rather than
    as an `OutOfMemoryError`. Do not lower them without reproducing that.

## Adding a dependency

```
1. add it to libs.versions.toml with a justification comment
2. reference it as libs.<alias> in the module that needs it
3. api only if it appears in a public signature
4. ./gradlew :<module>:dependencies --configuration runtimeClasspath -q  to confirm one version wins
```

## Never

- **Never apply a plugin in a module that a convention plugin could apply**, unless
  only that module needs it — `kotlin-jpa` is applied per module for exactly that
  reason, and disappears with JPA.
- **Never use `subprojects {}` or `allprojects {}`.** Convention plugins exist so
  configuration is opt-in and visible in the module that receives it.
- **Never disable the configuration cache to make a task work.** Fix the task.
- **Never add a Gradle module to express a layer.** Layers are packages; see
  `architecture-modules`.
- **Never commit generated sources.**

## Verify

```bash
./gradlew build
./gradlew :app:dependencies --configuration runtimeClasspath -q | grep -i <new-lib>
```

A second full `build` should be almost entirely `UP-TO-DATE`/`FROM-CACHE`; if it is
not, a task is declaring its inputs or outputs wrongly.
