---
name: persistence-choice
description: Which persistence model to use in barometr and why — jOOQ is the single model, JPA/Hibernate is being removed from identity, and this states the conditions under which an entity model would be admissible again plus the rules that apply if it ever is (ddl-auto validate, open-in-view false, locking, no entity across a boundary). Use when adding persistence to a module, considering Spring Data JPA, Hibernate or an @Entity, or when touching the remaining identity entities.
---

# Persistence: one model

**Apply when** adding persistence to a context, or when reaching for `@Entity`,
`JpaRepository` or Hibernate.

## The rule

**jOOQ is the persistence model for this system.** JPA/Hibernate exists today only in
`identity-impl` and is being removed — see `docs/backend-review.md` (D-3).

Do not add `spring-boot-starter-data-jpa` to any module.

## Why one model

1. **One deployable was carrying two.** Two transaction and flush semantics, two ways
   a query can be wrong, two sets of habits for a reviewer to hold.
2. **The schema uses Postgres properly.** `FOR UPDATE SKIP LOCKED`, `ON CONFLICT DO
   NOTHING`, partial indexes, `tstzrange`, `pg_trgm`, `pgvector`. Under JPA these come
   back as native queries in strings — the parts of the system that most need type
   checking, typed the least.
3. **jOOQ's generated code is derived from the migrations**, so a dropped column
   breaks compilation. JPA's equivalent is `ddl-auto: validate`, which fails at
   startup instead — later, and only for what the entity model happens to cover.
4. **Identity was small enough to convert**: four classes and two repositories. The
   stated reason for keeping it — "the code already works" — is the reason technical
   debt survives, so it was converted while it was cheap.

## When an entity model would be admissible again

State the case explicitly and get it agreed before writing code; all three must hold:

- a genuinely graph-shaped aggregate written and read as a whole, where identity-map
  and dirty-checking do real work,
- no analytical or set-based queries over the same tables,
- no dependence on Postgres-specific SQL in that aggregate's access paths.

"It is faster to write" is not one of them.

## If JPA is ever used here

Rules that applied to `identity` and would apply again:

5. **`ddl-auto: validate`, never `update` or `create`.** The schema belongs to the
   migration tool; `validate` turns entity/migration drift into a startup failure
   instead of a silent `ALTER`.
6. **`open-in-view: false`.** Rendering a response must not be able to trigger a query.
7. **The `kotlin-jpa` plugin is applied by the module that owns entities**, not by a
   convention plugin — JPA needs a no-arg constructor that Kotlin does not emit.
8. **An entity never crosses a module boundary.** Convert at the port:
   `UserEntity.toSnapshot()` returning a `UserSnapshot`. Exposing the entity couples
   every consumer to a column name.
9. **A controller never touches a repository.** It goes through the service or the
   published port (review E31).
10. **Services depend on a narrow port, not on `JpaRepository`.** `Users` and
    `RefreshTokens` name the four or five operations identity actually performs, so a
    service can be tested without a persistence context and the move to jOOQ stops at
    the adapter.
11. **Concurrency is explicit.** `@Lock(LockModeType.PESSIMISTIC_WRITE)` where two
    transactions would otherwise both read the same row and both act on it — the
    refresh-token rotation is the case that proves it.
12. **Bulk updates carry `@Modifying(clearAutomatically = true, flushAutomatically = true)`**,
    or the persistence context serves stale entities afterwards.
13. **No `EAGER` fetching and no bidirectional association added "for convenience"** —
    a bare `userId` is correct when nothing walks the graph.
14. **Computed properties stay unmapped** — `@Id` on a field puts the entity in
    field-access mode, which is why `UserEntity.roleNames` is a plain getter.

## Never

- **Never mix both models against the same table.**
- **Never use JPA to avoid learning the schema.** If the query is hard to express,
  that is information about the schema.
- **Never store a collection as a delimited string to avoid a join table** —
  `identity.users.roles` as CSV cannot be constrained, indexed or queried by role.

## Verify

```bash
grep -rn 'jakarta.persistence\|JpaRepository\|starter-data-jpa' --include='*.kt' --include='*.kts' modules platform shared app
```

After tranche 3 this prints nothing.
