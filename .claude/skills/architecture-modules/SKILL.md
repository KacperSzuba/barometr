---
name: architecture-modules
description: Module boundaries and dependency rules for the barometr backend — where a new class, package or Gradle module belongs, what may depend on what, and how contexts talk to each other. Use when adding a module or context, moving code between modules, deciding whether something is platform or domain, wiring a bean into :app, exposing something across a boundary, publishing or consuming an application event, or when a Modulith/ArchUnit boundary test fails.
---

# Module boundaries

**Apply when** creating or moving a module, deciding where a class lives, or making
two contexts talk to each other.

## The layout

A Gradle module is a **service candidate**: everything one bounded context needs to
run travels with it, so extracting it later is a move, not an untangling.

```
:app            assembly only — wiring, security chain, exception mapping. No domain logic.
:shared         value types. No Spring, no persistence, no HTTP.
:platform       technical capability with no domain meaning: http · jobs · storage.
:identity       accounts, tokens, sessions.
:ingestion      connectors, source registry, the raw archive.
:corpus         documents, versions, extracted text, chunks.
:legislative    acts, drafts, the legislative process.
```

Inside a context: `pl.barometr.<context>.api` is the published contract,
`pl.barometr.<context>.internal` is everything else, including all persistence.

> Being consolidated from the earlier 20-project `-api`/`-impl` layout — see
> `docs/backend-review.md` (D-1). Until tranche 1 lands, paths below still read
> `modules/<context>/<context>-impl/...`; the rules are unchanged either way.

## Rules

1. **A context's `internal` package is invisible to everyone else.** Reaching past
   `api` couples the caller to storage and service shapes that must stay free to
   change. Enforced by `ApplicationModules.verify()` and an ArchUnit rule in
   [ModularityTest.kt](app/src/test/kotlin/pl/barometr/ModularityTest.kt); add a rule
   there for each new context.
2. **`api` holds contracts and value types only** — interfaces, events, ids, DTOs.
   No Spring beans, no jOOQ, no HTTP. If a contract needs a framework to express
   itself, it is not a contract yet.
3. **`shared` is value types with no framework at all.** The moment shared code
   wants Spring, persistence or HTTP, it belongs to a context. `ContentHash`,
   `DomainException`, `Ids` are the whole of what qualifies.
4. **`platform` may not depend on any context**, because a technical capability that
   knows a domain is a context in disguise. Contexts depend on platform; never back.
5. **`:app` is the only module allowed to see every implementation**, and holds no
   domain logic — assembling the system is its entire purpose. Security chain,
   `@RestControllerAdvice` mapping and `main` live there and nowhere else.
6. **Anything crossing a boundary is a value type.** Never a persistence row, never a
   jOOQ record, never an entity. `UserLookupAdapter` returns `UserSnapshot` for
   exactly this reason — see
   [UserLookup.kt](modules/identity/identity-api/src/main/kotlin/pl/barometr/identity/api/UserLookup.kt).
7. **Prefer an event to a call when the caller does not need an answer.**
   `@ApplicationModuleListener` makes delivery asynchronous and transactional —
   Spring Modulith writes the publication to `event_publication` and retries it, so
   it is the outbox. `RawDocumentIngested` is the model: ingestion knows nothing
   about extraction, indexing or alerting, and they all hang off it.
8. **Prefer a published port when the caller does need an answer.** A read port
   (`UserLookup`, `SourceRegistry`) is an interface in `api`, implemented in
   `internal`. The implementing class is never referenced by name outside its context.
9. **No cross-schema foreign keys** — a FK across contexts is the same coupling in
   SQL, and it welds two schemas into one migration order. Integrity across a
   boundary is the pipeline's job; see `database-schema`.
10. **One context, one database schema, one changelog.** A context that reads another
    context's tables has skipped its `api`.

## Patterns to copy

- Contract, event and port for a context:
  [identity-api](modules/identity/identity-api/src/main/kotlin/pl/barometr/identity/api/)
- Boundary tests to extend when adding a context:
  [ModularityTest.kt](app/src/test/kotlin/pl/barometr/ModularityTest.kt)
- Assembly and nothing else:
  [BarometrApplication.kt](app/src/main/kotlin/pl/barometr/BarometrApplication.kt)

## Never

- **Never add a Gradle module with no code in it.** Five such modules exist today and
  two still run jOOQ codegen on every build (review D20).
- **Never put a controller, repository or `@Configuration` in `api`.** It stops being
  a contract the moment a consumer inherits a runtime from it.
- **Never let `:app` contain domain logic**, however small — it is the one place with
  no boundary to stop it growing.
- **Never reach into another context to avoid publishing a contract.** If the
  contract is missing, add it; the shortcut is permanent.
- **Never split a context into more Gradle modules to express layers.** Layers are
  packages. Modules are deployment units.

## Verify

```bash
./gradlew :app:test --tests 'pl.barometr.ModularityTest'
```

Then check that the new context appears in the printed module structure with the
dependencies you expect, and that nothing outside it names its `internal` package.
