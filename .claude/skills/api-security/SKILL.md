---
name: api-security
description: HTTP API and security rules for barometr — controllers and DTOs, jakarta validation, mapping DomainException to status codes, authorization on operator endpoints with @PreAuthorize, where the security chain lives versus token minting, JWT handling, and secret management per profile. Use when adding or changing an endpoint, a request or response type, an exception type, anything under Spring Security, or when deciding what a failure should return.
---

# API and security

**Apply when** adding or changing an endpoint, an error, or anything security-related.

## Shape of an endpoint

1. **Controllers live in the owning context's `internal` package** and own their
   routes — identity owns `/api/v1/auth`, ingestion owns `/api/v1/ingestion`. The application
   does not collect other contexts' controllers.
2. **A controller goes through a service or a published port, never a repository.**
   `MeController` reads through `UserLookup`, the module's own read port, so the day
   identity's storage changes the controller does not (review E31).
3. **Requests and responses are DTOs**, never entities or jOOQ records. A response DTO
   is part of the API contract and changes only deliberately.
4. **Validate at the edge** with `jakarta.validation` on the request DTO
   (`@field:NotBlank`, `@field:Email`, `@field:Size`), and let
   `MethodArgumentNotValidException` become a `400` with per-field detail in
   [ApiExceptionHandler](app/src/main/kotlin/pl/barometr/ApiExceptionHandler.kt).
   Bounds that come from an algorithm are commented as such — BCrypt ignores anything
   past 72 bytes, so that is where the maximum comes from.
5. **A controller does not decide status codes for domain failures.** Module code
   throws a `DomainException` carrying an `ErrorKind` and a stable machine-readable
   code; the application maps kind to status in one place. No module imports
   `org.springframework.http`, which keeps the same exception meaningful to a job or a
   CLI.
6. **`error(...)` is not an error response.** An unknown `?connector=` used to reach
   `error(...)` and come back as a 500 (review B8). Anything a caller can cause gets a
   `DomainException` — `UnknownConnectorException`, `InvalidBackfillWindowException` —
   carrying `NOT_FOUND`, `INVALID`, `CONFLICT`, `FORBIDDEN` or `UNAUTHENTICATED`.
   Keep `error(...)` for a state the code believes impossible.
7. **A POST that starts expensive work states its parameters in a body**, and the
   handler bounds them — a replay window is thousands of requests to somebody else's
   server.

## Authorization

8. **Every endpoint declares who may call it.** `@EnableMethodSecurity` is on;
   operator endpoints carry `@PreAuthorize("hasRole('OPERATOR')")`, as
   `BackfillController` does. Registration is open, so "authenticated" means "anyone
   who signed up" — which is how a multi-week crawl of a government registry was one
   POST away (review B6).
9. **A role that grants operator power cannot be self-assigned.** Registration grants
   `Role.USER` and nothing else; `OPERATOR` is a row somebody inserts deliberately
   into `identity.user_roles`, and the database refuses a role the code does not know.
10. **A claim name is declared once, where it is published.** `JwtClaims` lives in
    `identity.api` because minting and authorising are two places that must agree —
    they used to spell `"roles"` out separately, one typo from every request arriving
    with no authorities.
11. **The filter chain belongs to `:app`; token minting belongs to `identity`.** Only
    the application knows every context's routes, and identity has no business holding
    a list of them.
12. **Every rule in the chain that depends on a condition carries that condition in a
    comment.** CSRF is disabled *because* the API is bearer-only and reads no cookie;
    the day a session cookie is trusted, that line is wrong. Same for CORS.
13. **Authentication failures return a JSON body with a status, never a redirect** —
    the Next.js route guard keys its silent refresh off the status.

## Tokens and secrets

14. **Access tokens are short-lived** — that is what substitutes for a revocation list.
    Refresh tokens rotate, and a family is revoked as a unit when replay is detected.
15. **Only a hash of a refresh token is stored.** Plain SHA-256 is correct there: the
    input is already 256 bits of entropy and needs no work factor. Passwords use BCrypt.
16. **Validate `aud` as well as `exp`, `nbf` and `iss`.** The default validator does
    not check audience, which is the check most often missed —
    [JwtConfig](modules/identity/src/main/kotlin/pl/barometr/identity/internal/config/JwtConfig.kt)
    adds it explicitly.
17. **Nothing sensitive goes in a JWT claim.** The payload is base64, readable by
    anyone holding the token.
18. **Authentication failures are indistinguishable from one another.** An unknown
    e-mail and a wrong password return the same code and take the same time — otherwise
    response time becomes an account-enumeration oracle.
19. **Secrets come from the environment, and the prod profile has no fallback.**
    `${JWT_SECRET}` unset must stop the application from starting.
20. **Cross-instance state must be in the database, not in a field.** A grace window
    held in a `ConcurrentHashMap` turned a normal parallel refresh into a detected
    theft the moment a second replica existed; it is now a row lock and a timestamp,
    which work on any number of instances (review A3).

## The audit trail

21. **Refusals are recorded where they are produced**, in the two handlers in
    [ApplicationSecurityConfig](app/src/main/kotlin/pl/barometr/ApplicationSecurityConfig.kt).
    A refused request never reaches the audit filter — it is turned back inside the
    security chain — and that is also the last point at which the caller is still
    known, because the context is cleared on the way out. A denial nobody recorded is
    the entry the whole feature exists for, missing.
22. **The trail is append-only in the database**, by a trigger rather than a `REVOKE`:
    the application owns that schema and an owner's privileges are its own to restore.
    Nothing may `UPDATE`, `DELETE` or `TRUNCATE` it — including a retention job, until
    somebody writes down how long these must be kept.

## Never

- **Never expose an entity, a jOOQ record or an internal type from an endpoint.**
- **Never return an exception message to a client**; return a code.
- **Never add an endpoint without deciding its authorization** — "authenticated" is a
  decision only if you made it deliberately.
- **Never put a secret, a token or a password in a log line, a URL or a metric tag.**
- **Never widen a `permitAll` matcher to make something work.**

## Verify

```bash
./gradlew :app:test
```

A new endpoint needs a test that an unauthorized caller gets 401, an under-privileged
one gets 403, and a domain failure returns its code with the right status.
