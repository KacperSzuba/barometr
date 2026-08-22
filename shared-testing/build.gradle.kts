plugins {
    id("barometr.module")
}

// A test harness rather than a context: it ships on the test classpath of whatever
// needs it and depends on nothing of ours. Extracted the moment a second module
// wanted the same migrated Postgres — one copy is a file, two are a divergence
// waiting to happen.
dependencies {
    api(libs.testcontainersPostgres)
    // The search index is tested against a real node with the Polish analyser in it,
    // for the same reason the schema is tested against real Postgres: the analyser is
    // the thing under test, and a stub of it would only confirm what we assumed.
    api(libs.testcontainersElasticsearch)
    // The mail server digests are sent to in tests is a plain container.
    api(libs.testcontainersCore)
    api(libs.liquibaseCore)
    api(libs.jooq)
    api(libs.postgresql)
}
