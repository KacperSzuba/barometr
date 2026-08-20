plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "platform"
}

dependencies {
    api(project(":shared:shared-kernel"))
    implementation(project(":platform:platform-persistence"))
    implementation(libs.springBootStarterJooq)
    implementation(libs.shedlockSpring)
    implementation(libs.shedlockJdbc)
    // ShedLock's JDBC provider is built on JdbcTemplate, which this module uses
    // directly rather than inheriting by accident from the jOOQ starter.
    implementation(libs.springBootStarterJdbc)
    implementation(libs.springBootStarterActuator)

    // The queue's guarantees are concurrency guarantees, so they can only be
    // tested against a real Postgres. `JooqJobQueue` depends on nothing but a
    // `DSLContext` and a `Clock`, which keeps these tests free of a Spring context.
    //
    // Through the shared harness rather than a container of its own: two setups for
    // one migrated database is two things to keep in step, and two containers per
    // build to wait for.
    testImplementation(project(":shared:shared-testing"))
    testImplementation(libs.testcontainersJunit)
}
