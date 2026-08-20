---
name: naming-and-files
description: Naming and file-layout rules for barometr Kotlin code — what to call a class, method, property or file, how many types may live in one file, and how to replace generic verbs like process/handle/run/accept with names that state the domain action. Use when creating a file or type, naming or renaming anything, reviewing a diff for readability, or when a file starts collecting unrelated types.
---

# Naming and files

**Apply when** creating a file, naming a type or method, or reviewing a name.

A name is the only documentation that is read every time. In this repository the
recurring defect is not a wrong name — it is a name so general it could belong to
half the codebase, which is always a missing abstraction wearing a disguise.

## Rules

1. **A file is named after what it contains, and its name covers everything in it.**
   Kotlin's own style guide is explicit that several declarations may share a file
   when they are closely related and the file stays small — `DomainException.kt`
   holding `ErrorKind` beside it is right, because the name covers both. What is
   wrong is a file named after one of several unrelated siblings:
   `SourceRegistry.kt` held eight types including `IngestionMode` and `RunReport`, so
   finding either meant grepping (review E27). Thirty-one such files were split.
2. **No category files.** `Repositories.kt`, `AuthDtos.kt`, `IdentityErrors.kt`,
   `Ids.kt` name a bucket, not a thing. A file's history should be the history of one
   subject; a bucket file's history is noise. When in doubt, one type per file is
   never wrong — it is grouping that has to be justified.
3. **Name a method for the domain action it performs, in the vocabulary of its
   layer.** `insertIfAbsent`, `hasChangedSince`, `readTermChunk`, `revokeFamilyOf`,
   `reviewCompletedRun`, `declaredVolumes` are right. If a name could describe half
   the codebase — `process`, `handle`, `run`, `accept`, `execute`, `manage`, `doWork`
   — it is hiding an abstraction that has not been found yet.
4. **Generic verbs are admissible only where the abstraction is genuinely generic.**
   `JobQueue.claim`, `BlobStore.store` and `JobHandler.handle` describe mechanisms
   that really are domain-free. `Connector.fetch` and `RawDocumentSink.accept` do
   not: those callers know exactly what is being read and written.
5. **Name a type for what it means, not for the mechanism it uses.** `RclPages`
   builds URLs and should say so; `Tally` is a counter with no subject. Adapters are
   the exception — `JooqSourceRegistry`, `FilesystemBlobStore`,
   `RestClientSourceHttpClient` name their technology on purpose, because choosing
   between implementations is the reader's actual question.
6. **A boolean parameter is a naming failure.** `fetch(force = true)` tells the
   reader nothing at the call site. Use a sealed type or two named methods —
   `RobotsPolicy.Respect` / `RobotsPolicy.Exempt(legalBasis)` is the model.
7. **Do not encode a sentinel in a `String`.** `partition: String = ""` meaning
   "no partition" is a value nobody can spot at a call site; use `null` or a type.
8. **Constants say what, not how much.** `ANOMALY_FRACTION = 0.2` with the sentence
   explaining what a fifth of the average means, never a bare `0.2` in a condition.
9. **Test names are sentences about behaviour**, in backticks:
   `` `identical content is recognised and publishes nothing` ``. The name is the
   specification; if it needs a comment to be understood, rename it.
10. **Renaming is not cosmetic.** A public name that is wrong costs every reader
    after you; change it while the change is cheap.

## The rename table

Worked examples from this codebase — the shape of the fix, not just the rule:

All of these have been applied; they are kept as worked examples of the rule.

| Was | Is | Why |
|---|---|---|
| `IncrementalConnector.fetch(cursor, sink)` | `readChangesSince(cursor, sink)` | states what is read and from where |
| `BackfillConnector.fetchPartition(...)` | `readPartitionChunk(...)` | a chunk, not the partition — the whole point of the method |
| `RawDocumentSink.accept(payload)` | `archive(payload)` | the sink archives; "accept" describes the caller's manners |
| `RawDocumentSink.warn(warning)` | `recordSchemaWarning(warning)` | warning is not logging; it is recorded on the run |
| `ConnectorRunner.run(source, mode, partition)` | `readSourceOnce(...)` | one run of one source, said plainly |
| `JobWorker.poll()` / `dispatch(job)` | `claimAndRun()` / `runHandlerFor(job)` | what happens, not the loop shape |
| `ArchiveCompleteness.audit(id)` | `compareArchiveAgainstSource(id)` | the actual comparison |
| `RclPages` | `RclUrls` | it builds URLs |
| `RclDates` | `RclDateFormats` | it holds the site's two formats |
| `Tally` | *(deleted)* | the sink already counts; see review A4 |
| `NewJob.priority: Int` | `priority: JobPriority` | `priority = 42` compiled and meant nothing |

## File splits

Done: `SourceRegistry.kt` (eight types) split by subject; `AuthDtos.kt` and
`IdentityErrors.kt` dissolved into one file per request, response and failure;
`Connector.kt`, `RawDocumentSink.kt`, `SourceHttpClient.kt`, `JobQueue.kt` and
`BlobStore.kt` reduced to the type they are named after; every `*Properties`,
`*Factory` and `*Configuration` that had been sharing a file with its subject given
its own.

Two files deliberately keep two types: `DomainException.kt` (`ErrorKind` is its
parameter) and `ApiExceptionHandler.kt` (`ErrorResponse` is its output). The name
covers both in each case.

Still to move: `MeController` serves `/api/v1/me` and does not belong in the `auth`
package.

## Never

- **Never name a file for a plural category** (`*Dtos`, `*Errors`, `*Repositories`,
  `*Utils`, `*Helpers`). `Utils` in particular is a place where design goes to be
  forgotten.
- **Never leave a name that describes the framework rather than the intent** on a
  domain type — `SourceRegistry`, not `SourceJpaService`.
- **Never abbreviate** where the full word fits: `req`, `res`, `cfg`, `mgr`.
- **Never reuse a name for two meanings** in one context: if `partition` is both a
  key and a label, one of them needs a different word.

## Verify

```bash
for f in $(git ls-files '*.kt'); do n=$(grep -cE '^(internal )?(abstract |sealed |open |data |enum |value )*(class|interface|object) ' "$f"); [ "$n" -gt 1 ] && echo "$n $f"; done
```

Every line printed is a file to split, unless the extra types are private helpers.
