# Adding a context

A context is a candidate service. Add one only when it owns data and decisions that no
existing context should own — not to express a layer.

## 1 · Decide it is a context

- It owns at least one database schema.
- It has a contract other contexts would consume, or it consumes theirs.
- It could plausibly be deployed on its own one day.

If any answer is no, it is a package inside an existing context.

## 2 · Register it

`settings.gradle.kts` — add the name to the `listOf(...)` of contexts, which maps it
to `modules/<name>` and gives it the short project path `:<name>`.

`build.gradle.kts` for the module:

```kotlin
plugins {
    id("barometr.jooq-codegen")   // or barometr.module when it owns no schema
}

jooqCodegen { schema = "<context>" }

dependencies {
    api(project(":shared"))
    implementation(project(":platform"))
    // Other contexts, when this one's contract names their types.
    implementation(libs.springModulithStarterCore)

    testImplementation(project(":shared-testing"))
}
```

## 3 · Package layout

```
pl.barometr.<context>.api        contracts, events, ids, DTOs — no framework
pl.barometr.<context>.internal   everything else, including all persistence
pl.barometr.<context>.internal.jooq   generated, never edited
```

Mark the published surface for Modulith — `src/main/java/pl/barometr/<context>/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package pl.barometr.<context>.api;
```

Java, because Kotlin has no package-level annotations. Without it Modulith treats the
whole sub-package as internal, and every consumer of the contract is reported as a
boundary violation while real violations go unnoticed.

## 4 · Schema

- One schema, named after the context, created by its first changeset.
- Changeset ids `<context>-0001-…`; the master changelog states where the context's
  changelog is included.
- No foreign key to another context's schema.
- See the `database-schema` skill.

## 5 · Boundaries

Add the context's name to `CONTEXTS` in
[ModularityTest](app/src/test/kotlin/pl/barometr/ModularityTest.kt). That one line
puts it into both the "is it a module at all" check and the internals rule.

Nothing else enforces this: the build no longer has `-impl` projects to refuse.

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
- [ ] Listed in `settings.gradle.kts`
- [ ] `api` / `internal` packages, `package-info.java` naming the interface
- [ ] Its own schema and changelog, no cross-schema FK
- [ ] Added to `CONTEXTS` in `ModularityTest`
- [ ] Wired into `:app`, and only there
- [ ] Contains actual code — never an empty placeholder module
