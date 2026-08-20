---
name: library-first
description: Check what Spring, the JDK and the classpath already provide before writing any generic infrastructure in barometr — retry, backoff, rate limiting, caching, scheduling, HTTP clients, JSON, parsing standard formats, connection pooling, hashing. Use before implementing anything that sounds like solved plumbing, when adding a dependency, or when a class starts to look like a small framework.
---

# Use what is already there

**Apply when** about to write anything that sounds generic: retry, backoff, rate
limiting, caching, scheduling, an HTTP client, JSON handling, a parser for a standard
format, connection pooling, hashing, locking.

The failure mode this prevents is specific and has already happened here: a
hand-written HTTP client, token bucket, retry loop and robots.txt parser — 357 lines
replaced by 224 lines of integration. Two of the costs were not stylistic. The
hand-built client emitted **no Micrometer metrics and no trace spans**, silently
failing a stated observability requirement; the hand-written robots parser did prefix
matching only, ignoring `*` and `$` wildcards, so it would have crawled paths the
site forbade — a legal boundary, not a nicety.

## Procedure

1. **Name the problem in library terms** before writing a line: "per-host rate
   limiting with metrics", not "I need to slow this down".
2. **Look on the classpath first.** Spring Framework 7 and Boot 4 ship far more than
   most people assume — retry with jitter is in `spring-core`
   (`RetryTemplate`, `RetryPolicy.builder().jitter(...)`), `RestClient` is
   instrumented out of the box, `ApplicationEventPublisher` is a mediator, Modulith's
   `@ApplicationModuleListener` is a transactional outbox.
   ```bash
   ./gradlew :app:dependencies --configuration runtimeClasspath -q | grep -i <thing>
   ```
3. **Then look at what is already declared** in
   [gradle/libs.versions.toml](gradle/libs.versions.toml) — Resilience4j,
   crawler-commons, jsoup, ShedLock, uuid-creator and pgvector are already there, each
   with a comment saying why the framework did not cover it.
4. **Then look at Maven Central**, and check the version actually exists before
   writing it into the catalog.
5. **If nothing fits, say so explicitly** — in the commit message and in a comment at
   the top of the class: what was searched for, and why the result did not fit.
   Silence reads as "did not look".

## What is genuinely ours to write

Domain vocabulary, ports, and the glue between libraries. `SourceHttpClient` is the
right shape: retries come from the framework, rate limiting from Resilience4j,
robots.txt from crawler-commons, and what remains is turning HTTP into the four
outcomes connectors actually need — fetched, unchanged, refused, failed.

## Rules

1. **Never hand-roll JSON.** An `ObjectMapper` is injectable everywhere. A payload
   built with `"""{"sourceId":"$id"}"""` and parsed back with Jackson two classes
   later is the example this rule comes from (review B7) — a quote in any value breaks
   it, and the failure is a job that dead-letters after five attempts.
2. **Never hand-roll a cache with eviction.** If a `Map` plus a sweep loop is
   appearing, either the data belongs in the database (usually — see A3, where a
   unique index for exactly that lookup already existed) or Caffeine belongs in the
   catalog.
3. **Never hand-roll retry, backoff or jitter.** `RetryTemplate` has all three, and a
   `Retry-After` header still has to be honoured explicitly — see
   [RestClientSourceHttpClient.kt](platform/src/main/kotlin/pl/barometr/http/internal/RestClientSourceHttpClient.kt).
4. **Never hand-roll a parser for a specified format** — robots.txt, HTML, MIME,
   dates, URIs. Someone else has already handled the malformed cases you have not
   thought of yet.
5. **Prefer the instrumented option.** Between two libraries, the one that reports
   itself to Micrometer wins, because throttling and failure become visible on a
   dashboard instead of surfacing as unexplained slowness.
6. **A new dependency needs a one-line justification in the catalog** saying what in
   Spring or the JDK does *not* cover it. If that line is hard to write, the
   dependency is probably unnecessary.
7. **Do not add a mocking framework, an assertion DSL or a utility library** because
   it is familiar. This project deliberately uses hand-written fakes and
   `kotlin.test`; see `testing`.

## Never

- **Never reimplement something to avoid one unwanted feature** of a library. Wrap it.
- **Never copy an implementation out of a library** into the repository to "avoid a
  dependency" — it forks a maintained thing into an unmaintained one.
- **Never write the words "simple implementation for now"** about infrastructure. It
  is never replaced, and it is never simple by the time anyone tries.

## Verify

Before finishing a class that feels generic, answer in one sentence: which library
was considered, and why it did not fit. If the answer is "none was considered",
go back to step 2.
