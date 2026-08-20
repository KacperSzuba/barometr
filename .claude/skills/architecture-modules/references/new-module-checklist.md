# Adding a context

A context is a candidate service. Add one only when it owns data and decisions that no
existing context should own — not to express a layer.

## 1 · Decide it is a context

- It owns at least one database schema.
- It has a contract other contexts would consume, or it consumes theirs.
- It could plausibly be deployed on its own one day.

If any answer is no, it is a package inside an existing context.

## 2 · Register it

`settings.gradle.kts` — add under the right heading, with the one-line comment saying
what it is for. Keep the existing section comments accurate.

`build.gradle.kts` for the module:

```kotlin
plugins {
    id("barometr.jooq-codegen")   // or barometr.module when it owns no schema
}

jooqCodegen { schema = "<context>" }

dependencies {
    implementation(project(":shared"))
    implementation(project(":platform"))
    // Other contexts: their published contract only.
    implementation(libs.springModulithStarterCore)

    testImplementation(project(":shared:shared-testing"))
}
```

## 3 · Package layout

```
pl.barometr.<context>.api        contracts, events, ids, DTOs — no framework
pl.barometr.<context>.internal   everything else, including all persistence
pl.barometr.<context>.internal.jooq   generated, never edited
```

Mark the published surface for Modulith:

```kotlin
@org.springframework.modulith.NamedInterface("api")
package pl.barometr.<context>.api
```

## 4 · Schema

- One schema, named after the context, created by its first changeset.
- Changeset ids `<context>-0001-…`; the master changelog states where the context's
  changelog is included.
- No foreign key to another context's schema.
- See the `database-schema` skill.

## 5 · Boundaries

Add to [ModularityTest](app/src/test/kotlin/pl/barometr/ModularityTest.kt):

```kotlin
noClasses()
    .that().resideOutsideOfPackage("pl.barometr.<context>..")
    .should().dependOnClassesThat().resideInAPackage("pl.barometr.<context>.internal..")
    .because("<context> publishes its contract in .api; reaching past it couples callers to internals")
    .check(classes)
```

## 6 · Wire it

- `app/build.gradle.kts` — add the project dependency.
- Nothing else in `:app`: no configuration class for the context's own beans, which
  belong to the context.

## 7 · Prove it

```bash
./gradlew :app:test --tests 'pl.barometr.ModularityTest'
./gradlew check
```

The printed module structure must show the context with exactly the dependencies you
intended — no more.

## Checklist

- [ ] It owns data and decisions, and is not a layer
- [ ] `settings.gradle.kts` entry with its comment
- [ ] `api` / `internal` packages, `@NamedInterface` on `api`
- [ ] Its own schema and changelog, no cross-schema FK
- [ ] ArchUnit rule added
- [ ] Wired into `:app`, and only there
- [ ] Contains actual code — never an empty placeholder module
