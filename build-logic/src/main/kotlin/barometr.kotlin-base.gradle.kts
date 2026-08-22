import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        // Spring Framework 7 annotates nullability with JSpecify. Strict mode turns
        // those annotations into real Kotlin types instead of platform types, so a
        // nullable Java return cannot slip into a non-null Kotlin value unchecked.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Test classes run at the same time; the methods inside one do not.
    //
    // The split is not arbitrary. A class owns its fixture — its own database, cloned
    // for it, cleared in `@BeforeEach` — and its methods share that fixture by design,
    // so running them together would have each one truncating tables the next is
    // reading. Between classes there is nothing to share, which is what makes that
    // level safe to parallelise and where the time actually is.
    //
    // The two fixtures that genuinely cannot be cloned — the application's own database
    // and the search index — are held under `@ResourceLock` by the handful of tests
    // that use them.
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")

    // One JVM per module, deliberately.
    //
    // `maxParallelForks` was tried and taken out: the containers are JVM singletons, so
    // four forks meant four Postgres nodes and four Elasticsearch nodes for one module —
    // and the search tests timed out waiting for a node that never got the memory to
    // start. The parallelism is already there without it. Gradle runs the modules'
    // test tasks side by side, and inside each one the classes run concurrently against
    // databases of their own, which is where the time was.

    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}
