# barometr

Ingests the Polish legislative process — Sejm, RCL — archives exactly what each
source returned, and derives everything else from that archive.

Kotlin 2.3 · Spring Boot 4 · Postgres 16 with pgvector · jOOQ · Gradle with
convention plugins in an included build.

## Running it

```bash
docker compose up -d          # Postgres with pgvector on 5432, Elasticsearch on 9200
./gradlew :app:bootRun        # local profile, no further setup needed
```

`SPRING_PROFILES_ACTIVE=prod` requires `DATABASE_URL`, `JWT_SECRET` and `BLOB_ROOT`.
None has a production fallback, deliberately: a missing secret must stop the
application rather than sign tokens with a known key, and a default blob root would
write the archive somewhere that disappears with the container.

### Sending mail

Digests go out over SMTP, so a provider is `spring.mail.*` and nothing more —
Postmark and SES both speak it. Without `spring.mail.host` there is no transport at
all: digests still close and appear in the API, and nothing is lost. That is the right
default for a developer machine.

| Setting | What it is |
|---|---|
| `spring.mail.host` · `.port` · `.username` · `.password` | the provider's SMTP endpoint |
| `app.alerts.email.from` | the `From:` address; its domain is the one below |
| `app.alerts.email.unsubscribe-base-url` | where an unsubscribe link points |
| `app.alerts.email.webhook-secret` | what a provider must present to report a bounce; unset means the webhook refuses everything |

**Three things this code cannot do for you**, and the mail will be filtered without
them: publish SPF, DKIM and DMARC records for the sending domain; keep alerts on a
different subdomain from anything marketing goes out on, because sharing a reputation
is the quickest way to make alerts land in spam; and point the provider's bounce and
complaint webhooks at `POST /api/v1/alerts/email-events`. That endpoint takes this
system's own shape — `{"address": …, "event": "bounced"|"complained", "detail": …}` —
so connecting a provider means a small adapter from theirs, written against a payload
recorded from the real account.

```bash
./gradlew check                    # tests, module boundaries, modularity
./gradlew :<module>:generateJooq   # after any schema change
```

Tests run against the same Postgres image production uses, migrated by the project's
own changelog, and against an Elasticsearch node built from `infra/elasticsearch` —
the analyser is the thing under test, so a stub of it would prove nothing. Docker must
be running, and the first search test builds the image once.

Test classes run concurrently, and each one gets **its own database**, copied from the
migrated template with `CREATE DATABASE … TEMPLATE` — about seventy milliseconds, so a
class clearing a table is clearing its own copy. The methods inside one class do not
run concurrently: they share that class's fixture by design. The two fixtures that
cannot be copied — the application's own database and the search index — are held under
a `@ResourceLock` by the handful of tests that use them.

### What runs on a push

[`.github/workflows/backend.yml`](.github/workflows/backend.yml) runs `./gradlew check`
on every push and pull request — the tests, the module boundaries and the modularity
rules, which are the only thing enforcing those boundaries since each context became a
single module. Documentation-only changes are skipped, and a second push cancels the
first run rather than queueing behind it.

A merge to `main` also builds the image from [`Dockerfile`](Dockerfile) and pushes it
to GHCR **tagged with the commit**, never `latest`: which code is running has to be
answerable afterwards, and rolling back is redeploying the tag that was there before,
which only works when tags never move. The dependency graph is submitted from `main` so
a new advisory becomes an alert here rather than a thing somebody reads about.

**There is no deploy step, because there is nowhere to deploy to yet.** The image is
built and pushed; pointing it at a staging environment, and putting the production one
behind a manual approval, is the next thing this workflow needs and the one part of it
that cannot be written without the environment existing.

Running the image needs `DATABASE_URL`, `JWT_SECRET` and `BLOB_ROOT` — the last a
mounted volume, until the blob store becomes S3. A default for it would write the
archive into a container layer, and the layer would go when the container did.

The schema is managed by Liquibase; the manifest is
`platform/src/main/resources/db/changelog/master.yaml`. A database created before the
move from Flyway still carries `flyway_schema_history` and will refuse to migrate —
`docker compose down -v` once, and it rebuilds from scratch.

## How it is laid out

```
app             assembly: wiring, security chain, error mapping. No domain logic.
shared          value types. No Spring, no persistence, no HTTP.
shared-testing  test harness: a migrated Postgres and a movable clock.
platform        technical capability with no domain meaning: http · jobs · storage
modules/        one bounded context each — identity, sources, ingestion (with the
                connectors that read each source), corpus, legislative, search,
                profiles, alerts
infra/          the Elasticsearch image, which is built rather than pulled: the
                Polish analyser ships as a plugin Elastic distributes separately
build-logic/    convention plugins, as an included build
```

Twelve Gradle projects, one per thing that could become a service. It was twenty, split
`-api`/`-impl` and by technical layer, which meant extracting any one context would
have meant taking nine projects with it.

Each context publishes a contract in `pl.barometr.<context>.api` and keeps
everything else — including all persistence — in `pl.barometr.<context>.internal`.
Contexts talk through published ports or application events, never through each
other's internals, and never through a foreign key across schemas.

The whole thing is one deployable. The boundaries exist so that it does not have to
stay one.

## Working on it

Conventions are in [.claude/skills/](.claude/skills/) — one skill per area, indexed
in [.claude/skills/README.md](.claude/skills/README.md). They are written for an AI
agent and are just as usable as the project's coding standard.

[docs/backend-review.md](docs/backend-review.md) holds the reasoning behind them:
every known defect with its evidence, the four architectural decisions in flight
(module consolidation, Flyway → Liquibase, one persistence model, naming), and the
refactoring roadmap.
