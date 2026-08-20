plugins {
    id("barometr.module")
}

// A test harness rather than a context: it ships on the test classpath of whatever
// needs it and depends on nothing of ours. Extracted the moment a second module
// wanted the same migrated Postgres — one copy is a file, two are a divergence
// waiting to happen.
dependencies {
    api(libs.testcontainersPostgres)
    api(libs.liquibaseCore)
    api(libs.jooq)
    api(libs.postgresql)
}
