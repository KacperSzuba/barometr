plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "platform"
}

// Everything technical that carries no domain meaning: the single door to the
// outside world, the durable job queue, content-addressed object storage, and the
// Postgres extensions every other schema rests on.
//
// One module rather than four. They were split by mechanism, which meant a context
// that wanted to fetch a URL and enqueue a job depended on three projects and got
// no isolation for it — none of the four has ever changed without the others.
dependencies {
    api(project(":shared"))

    // jOOQ is part of this module's surface: repositories in every context take a
    // `DSLContext`, and the Postgres extensions carry the typed range bindings.
    api(libs.jooq)
    api(libs.jooqPostgresExtensions)

    // `api`, not `implementation`: the HTTP factory's signature exposes
    // `RestClient.Builder`, so consumers need it on their compile classpath.
    api(libs.springBootStarterRestClient)

    implementation(libs.springBootStarterJooq)
    implementation(libs.springBootStarterWeb)
    // Retry lives in Spring Framework 7 core — backoff, jitter, max delay and
    // retryable-exception predicates included. No third-party retry needed.
    implementation(libs.springBootStarter)
    implementation(libs.resilience4jRateLimiter)
    implementation(libs.resilience4jMicrometer)
    implementation(libs.crawlerCommons)
    implementation(libs.shedlockSpring)
    implementation(libs.shedlockJdbc)
    // ShedLock's provider is built on JdbcTemplate, used directly here rather than
    // inherited by accident from the jOOQ starter.
    implementation(libs.springBootStarterJdbc)
    implementation(libs.springBootStarterActuator)
    // Object storage. `implementation`, because nothing outside this module names a
    // storage type — the contexts see `BlobStore` and know nothing of what is behind it.
    implementation(libs.googleCloudStorage)

    runtimeOnly(libs.postgresql)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
