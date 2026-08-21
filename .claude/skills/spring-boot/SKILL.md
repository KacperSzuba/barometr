---
name: spring-boot
description: Spring Boot 4 and Framework 7 conventions for barometr — which starters exist and which were renamed or split, Jackson 3 under tools.jackson, constructor injection, @ConfigurationProperties instead of @Value, transaction boundaries, virtual threads, profiles, and fail-fast configuration. Use when adding a bean, controller, configuration class or starter, binding configuration, setting a transaction boundary, or when the application fails to start or a bean is not found.
---

# Spring Boot 4 in this project

**Apply when** adding a bean, binding configuration, choosing a starter, or setting a
transaction boundary.

## Boot 4 facts that memory gets wrong

These are current for the versions in
[gradle/libs.versions.toml](gradle/libs.versions.toml) (Boot 4.1, Kotlin 2.3) and are
the errors most likely to be introduced from habit:

1. **Autoconfiguration is split per technology.** `liquibase-core` on the classpath
   alone never runs — `spring-boot-starter-liquibase` wires it. There is no
   auto-configured `RestClient.Builder` without `spring-boot-starter-restclient`, and
   with it come the `spring.http.client.*` properties and the Micrometer
   instrumentation that made `RestClient` the right choice.
2. **Jackson 3 lives under `tools.jackson`**, not `com.fasterxml.jackson`. Imports are
   `tools.jackson.databind.ObjectMapper`, `tools.jackson.module.kotlin.kotlinModule`.
3. **The resource-server starter was renamed** to
   `spring-boot-starter-security-oauth2-resource-server`; the old name is deprecated.
4. **Retry is in Spring Framework core** — `RetryTemplate`, `RetryPolicy.builder()`
   with `delay`, `multiplier`, `maxDelay`, `jitter` and typed `includes`. No
   third-party retry belongs on this classpath.
5. **`kotlin-spring` (all-open) is applied by the convention plugin**, because Kotlin
   classes are final and CGLIB proxying of `@Configuration` fails at startup, long
   after compilation said everything was fine.

## Beans and configuration

6. **Constructor injection only.** No field injection, no `@Autowired` on properties,
   no setter injection. A component that cannot be constructed in a test is a design
   error.
7. **Configuration binds to a `@ConfigurationProperties` data class** with defaults,
   discovered by `@ConfigurationPropertiesScan` on the application class. **Never
   `@Value`** (review D26). A properties class gives every setting a documented home,
   which a `@Value` string does not.
8. **Validate configuration at construction, so a bad value stops startup.**
   `RclProperties.RobotsSetting.toPolicy()` throws when an exemption carries no written
   basis, and `RclConnectorConfiguration` calls it while building the bean — the
   application refuses to start rather than crawling quietly.
9. **Fail loudly when a setting makes the component useless; warn when it only makes
   it smaller.** `reportUnwrittenSelectors` is the model: `check(...)` when the walk
   cannot proceed, `log.warn` when only one step is unavailable.
10. **`@Configuration` classes wire collaborators that are not annotated components.**
    Prefer this over sprinkling `@Component` on classes that need constructor
    arguments from properties — see `SejmConnectorConfiguration`.
11. **The profile decides the danger.** `application-local.yml` may carry a
    development fallback; `application-prod.yml` must not — `${JWT_SECRET}` with no
    default is deliberate, so a missing secret stops the application instead of signing
    with a known key.

## Transactions

12. **`@Transactional(readOnly = true)` on read paths**, plain `@Transactional` on
    writes, placed on the repository or the service that owns the unit of work — not
    on a controller.
13. **Comment a deliberate absence.** `AuthService.refresh` is intentionally not
    transactional because `RefreshTokenService.rotate` suppresses rollback for the
    theft branch, and an outer boundary would undo the revocation it just performed.
14. **`noRollbackFor` when an exception must not undo what was written** — revoking a
    stolen token family and then throwing is the case that justifies it.
15. **Never open a transaction around a network call.** A connector fetch inside a
    transaction holds a pooled connection for the length of somebody else's server.
16. **Publishing an event that an `@ApplicationModuleListener` consumes requires a
    transaction.** That listener runs *after commit*, so with no transaction open the
    publication is written to the register and never delivered — nothing throws,
    nothing logs, and everything downstream of it silently stops. `RawDocumentArchiver`
    is `@Transactional` for this reason and no other: eight thousand documents were
    archived, eight thousand publications recorded, and not one of them handled.
    A test that publishes the event itself cannot catch this — it brings its own
    transaction. Drive the real producer.

## Runtime

16. **Virtual threads are on.** Blocking is fine; a scheduled method plus blocking IO
    costs no platform thread. Do not add an executor pool without a reason.
17. **`ApplicationEventPublisher` for anything the caller does not need an answer
    from**, and `@ApplicationModuleListener` on the consumer side — Modulith persists
    the publication and retries it, which is what makes it an outbox.
18. **Actuator exposure stays explicit** (`health`, `modulith`). Adding an endpoint is
    a decision, not a default.
19. **A behaviour that arrives by autoconfiguration is asserted somewhere.** Scheduling
    is enabled on this classpath by Boot rather than by anything this project writes;
    that is fine until an upgrade changes it, which is why
    `ApplicationContextTest` checks that scheduled tasks are registered.

## Never

- **Never use `@Value`** for anything other than a genuinely one-off literal — and
  there are none here.
- **Never put business logic in a `@Configuration` class**; it wires, it does not
  decide.
- **Never rely on component scanning to reach across a module boundary.** A bean in
  another context is reached through its published port.
- **Never expose an entity or a jOOQ record from a controller.**
- **Never let `spring.jpa.hibernate.ddl-auto` be anything but `validate`** for as long
  as JPA exists here — the schema belongs to the migration tool.

## Verify

```bash
./gradlew :app:compileKotlin
docker compose up -d && ./gradlew :app:bootRun
```

The startup log must show the connectors and job handlers registering, and no
`@ConfigurationProperties` binding warnings.
