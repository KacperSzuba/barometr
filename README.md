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

`SPRING_PROFILES_ACTIVE=prod` requires `DATABASE_URL`, `JWT_SECRET` and — for the
default `gcs` storage — `GCP_PROJECT`. None has a production fallback, deliberately: a
missing secret must stop the application rather than sign tokens with a known key.

### Where the archive is kept

The archive is the one thing here that cannot be recomputed, so in production it goes
to Google Cloud Storage: `app.storage.kind: gcs` and a project. Buckets are
`<prefix>-raw`, `-derived` and `-exports` — prefixed because a bucket name is global to
all of Google Cloud — and the application creates any that are missing when it starts,
so a wrong project stops it rather than failing the first ingestion run of the night.

**No credentials in configuration.** On Google Cloud the workload proves who it is by
its own identity, and on a developer's machine `gcloud auth application-default login`
does. That is why this uses Google's own client rather than the S3 compatibility layer:
that layer needs HMAC keys, which are a long-lived secret to leak.

`filesystem` is still there for a single-machine deployment, and then `BLOB_ROOT` must
name a mounted volume. `docker compose` runs a storage emulator on 4443 for trying the
`gcs` path locally; the default stays `filesystem`, because it needs nothing running and
leaves the blobs where they can be opened.

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

**One Postgres for the whole build**, started before any module's tests and stopped
when the build ends — the same container code generation reads. Nine modules each
starting their own and re-running the same changesets was most of what a test run spent
its time on.

**Where there is no Docker daemon, point the build at a Postgres that is running:**

```bash
./gradlew check -Pbarometr.postgres.url=jdbc:postgresql://localhost:5432
BAROMETR_POSTGRES_URL=jdbc:postgresql://localhost:5432 ./gradlew check   # same thing, for an agent
```

It has to be Postgres 16 with `pgvector` — the schema declares `vector` columns — and it
is migrated from nothing by this project's own changelog, so what the generated code and
the tests see is the schema the migrations produce either way; what differs is who
started the process. `barometr.postgres.username` and `.password` go with it, and default
to `postgres`. The template and every per-class copy are dropped and remade on each run,
because a server that outlives the build still holds the last one's. The tests needing
something other than Postgres — the search index, the mail server, the storage emulator —
still need Docker and are the only ones that fail without it.

Inside it, each test class gets **its own database**, copied from the migrated template
with `CREATE DATABASE … TEMPLATE` — about seventy milliseconds, so a class clearing a
table is clearing its own copy and classes can run side by side. The methods inside one
class do not: they share that class's fixture by design. The two fixtures that cannot be
copied — the application's own database and the search index — are held under a
`@ResourceLock` by the handful of tests that use them.

Connections are pooled, which is worth more than any of it: without a pool jOOQ opens
one per statement, and the suite spent most of its life in TCP handshakes. Run from an
IDE there is no build to hand a container over, so one is started per JVM instead —
`-Pbarometr.test.ownContainers=true` forces that path from Gradle too, which is the
first thing to try if the shared one is ever suspected.

Every container is capped at a couple of cores, here and in `compose.yaml`: a database
that takes every core it can see is why a build feels slow when it is actually the
editor that is starving.

### Following one document

Every request, job and outgoing call is an observation, and with tracing on that means a
span. Trace and span ids appear in every log line whether or not anything is exporting,
which is most of the value on a developer's machine.

The two places a trace would otherwise end are closed deliberately. A job carries the
`traceparent` of whoever queued it in its own row, because the gap between queueing and
running is minutes and machines wide and no thread-local survives it. A module listener
runs after its transaction commits, on another thread, and keeps the context because the
executor is decorated to carry it — which is also why work inside a job runs in an
observation rather than only in a span: **the observation is what the next thread
inherits.**

| Setting | What it does |
|---|---|
| `OTLP_ENABLED` | off by default — with exporting on and no collector listening, the exporter retries into a closed port and fills the log |
| `OTLP_ENDPOINT` | where a collector is, when there is one |
| `TRACING_SAMPLE` | everything by default: a tenth of a document's journey is a tenth of a story, and at this volume it costs nothing |

`/actuator/prometheus` and `/actuator/metrics` are exposed and, like everything else,
authenticated. A metrics endpoint states how many users, how many documents and which
jobs are failing; opening that to the internet because a scraper cannot hold a token is
a decision for a deployment to make on its own network, not a default shipped here.

### Signing in

A password, a rotating refresh token, and — for an account that asks for it — a second
factor. Three things are worth knowing before reading the code.

**A session is a refresh-token family.** One login issues one family; every token
descending from it belongs to the same device, and `identity.session` is what that
family looks like to the person who owns it: user agent as sent, address, last seen.
`GET /api/v1/sessions` lists them and marks the one the request came on, from the `sid`
claim in the caller's own token. Ending one revokes the family, so it stops working on
every instance at once — the access token already issued keeps working until it expires,
at most fifteen minutes, because there is deliberately no revocation list for those.

**A workspace may insist on things.** An organisation's account has seats, three roles
and invitations; two of its settings change how signing in works for its members. When it
requires a second factor, a member who has not enrolled is signed in with a token marked
`enrol` and reaches the enrolment routes and nothing else — refusing them outright would
leave them, and the administrator who turned the policy on, with no way to comply. When it
asks for shorter sessions, the strictest timeout among somebody's workspaces wins over the
deployment default.

**The second factor is TOTP, and turning it on is two steps.** `POST /api/v1/auth/2fa`
hands back a secret and an `otpauth://` URI; nothing about signing in changes until a
code from it comes back to `/2fa/confirmation`, which is also when the ten recovery codes
are shown — once. A password then buys `202` and a challenge rather than tokens, and the
challenge plus a code buys the tokens. A device that answered the factor may be remembered
for thirty days and sign in with the password alone — a deliberate weakening, revocable
from one route, and gone the moment the factor is turned off.

| Setting | What it does |
|---|---|
| `TOTP_KEY` | encrypts the shared secrets. Unset, the application refuses to enrol anybody rather than storing second factors in the clear |
| `TOTP_SALT` | derives the key from it. Not a secret, and it must not change once anybody is enrolled |
| `app.identity.session.idle-timeout` | how long a device may go quiet before it has to sign in again — fourteen days by default, and overridden by any workspace that asks for less |
| `app.identity.geoip.database-path` | a MaxMind `.mmdb` file, if the deployment has one. Unset, the device list shows addresses without a place beside them; set to something unreadable, the application refuses to start |
| `app.identity.workspace.invitation-base-url` | where an invitation link points |

### Which industries a law concerns

The question the product is sold on: a company says "we are in 41.20.Z", and something
has to connect that to a bill about building work. Nothing in a title says so in those
terms, so every act and draft is read against a **lexicon** — a list of Polish phrases
with the PKD code each one points at, and how much a single occurrence of it is worth as
evidence. It lives in
[`pkd-lexicon.json`](modules/taxonomy/src/main/resources/taxonomy/pkd-lexicon.json) and
is meant to be edited: that file is the knowledge, and the matcher around it is
deliberately dull.

Terms are written as **stems**, because Polish inflects everything a law is about:
`transporcie drogow` matches *drogowym* and *drogowego* alike, and a lexicon of whole
words would have to list every case ending or match nothing. Evidence combines rather
than adding — `1 - Π(1 - w)` — so two hints are surer than either alone and no pile of
weak ones ever becomes certainty.

What that buys is a number per code, and the number decides what happens to it:

| Confidence | What it means |
|---|---|
| below `app.taxonomy.floor-confidence` | not recorded at all — a lone weak stem is not a question worth putting to anybody |
| below `app.taxonomy.acceptance-threshold` | recorded as pending: it routes nothing and waits in the review queue at `GET /api/v1/taxonomy/review`, which shows each one with the law's title and the words that caught it |
| at or above it | accepted, and alerts route on it from that moment |

**A verdict a person has looked at is never re-decided by a machine.** A code somebody
rejected does not come back accepted on the next reading; that is a `CHECK`-shaped rule
written into the upsert, and it is what makes the queue worth working through.

**Correcting the lexicon is how coverage improves**, and it costs an edit and a version
bump: a new `version` in that file has no progress recorded against it, so the walk over
the archive starts again from the beginning and every act and draft meets the terms that
have just been fixed. Live documents are classified as they are recorded; the walk exists
for everything that was already stored, which on the day the classifier ships is all of
it. `taxonomy.verdicts{status="accepted"|"pending"}` is how much of the archive carries
an industry at all — a profile watching an industry nothing is tagged with is a
subscription to silence, and silence is what a working alert engine also looks like.

### The public API

`/api/v1/public/**` is open. No account, no key, sixty requests an hour by address —
enough to try it from a terminal, which is the only way anybody ever evaluates an API:

```bash
curl -s https://api.barometr.example/api/v1/public/consultations | jq '.consultations[0]'
```

A key raises the rate and nothing else. Every tier sees the same data — that is what makes
it public — and what differs is how fast it may be asked for: 600 an hour for a registered
key, 3 000 for a newsroom, 30 000 for a partner. Make one under `/api/v1/me/api-keys`
while signed in; it is shown once and stored as a hash.

```python
import httpx

r = httpx.get(
    "https://api.barometr.example/api/v1/public/consultations",
    headers={"X-Api-Key": "brmtr_..."},
)
print(r.headers["X-RateLimit-Remaining"], "left this hour")
```

Every response carries `X-RateLimit-Limit`, `-Remaining` and `-Reset`, refused or not, and
`X-Attribution` — **the one condition of use: say where the data came from.** Whole-dataset
downloads (`/consultations/csv`) need a key with the `bulk` scope, because that is where
serving a public API stops being cheap.

The limiter is a token bucket in Postgres rather than in memory or in Redis: a bucket held
in a process is a bucket per replica, and at these volumes one indexed upsert per request
is not the bottleneck. The trade is written down in
[the migration](platform/src/main/resources/db/changelog/platform/0006-rate-limit.sql).

### Your data, and getting rid of it

Two rights this implements rather than describes, both under `/api/v1/me`.

**A copy of everything** is `POST /api/v1/me/export`: a job reads every context that
holds anything about the account, writes one JSON file per request into the exports
bucket, and the account downloads it once. It expires after a week and the sweep deletes
the file with the row — an export is the most concentrated collection of somebody's data
this system ever produces, and leaving it behind a URL for ever would mean exercising a
right made the data easier to take. Nothing that proves anything is in it: no password
hash, no TOTP secret, no token hashes.

**Closing the account** is `DELETE /api/v1/me`, with the password again. Every context
that holds personal data implements
[`PersonalDataStore`](shared/src/main/kotlin/pl/barometr/shared/PersonalDataStore.kt) and
Spring hands all of them to one orchestrator, so a context added next year is included by
existing rather than by somebody remembering to add a line. It runs in one transaction:
half a deletion is worse than either outcome.

What survives is named in the response rather than left to be discovered — the audit
trail, whose entries are hash-chained and cannot be removed without breaking the chain for
everybody else's, and the suppression list, which exists to honour an earlier "stop
mailing me". `AccountClosureTest` closes an account with data in three schemas and counts
what is left in the database itself, which is the only honest way to check this.

The search index is deliberately not in that list: it holds acts and drafts, and no
profile, keyword or address ever reaches it. That is worth stating because an index is the
usual place data survives a deletion.

| Setting | What it does |
|---|---|
| `app.identity.privacy.export-retention` | how long a finished export can be downloaded — a week |
| `app.identity.privacy.credential-retention` | how long a revoked session or spent token stays on record — ninety days |
| `app.alerts.retention.notifications` · `.decisions` | two years for what somebody was told, one for why they were not |

### The API contract

Every route lives under `/api/v1`, signing in included, and the contract is generated
from the controllers by springdoc rather than written beside them — a document written
by hand is a second description of the same thing, and the two disagree the first time
somebody adds a field in a hurry. `GET /v3/api-docs` serves it, behind authentication
like everything else.

`OpenApiContractTest` writes it to `app/build/openapi/openapi.json` while the suite
runs, and CI uploads that file from every run, so a pull request that changes the API is
reviewable as a diff of its contract. The web application generates its TypeScript
response types from the same file; nothing on either side is typed twice by hand.

The change policy is the ordinary one and worth stating: **a field may be added without
a new version, and removed only after a transition period in which it is documented as
going.** The `v1` in the path changes when a response stops meaning what it meant.

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

Running the image needs `DATABASE_URL`, `JWT_SECRET` and `GCP_PROJECT`; see *Where the
archive is kept* above for the storage side of it.

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
                connectors that read each source), corpus, legislative, taxonomy,
                search, profiles, alerts, audit
infra/          the Elasticsearch image, which is built rather than pulled: the
                Polish analyser ships as a plugin Elastic distributes separately
build-logic/    convention plugins, as an included build
```

Thirteen Gradle projects, one per thing that could become a service. It was twenty, split
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
