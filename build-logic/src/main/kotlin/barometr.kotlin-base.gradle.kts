import pl.barometr.build.MigratedPostgresService
import pl.barometr.build.PostgresConnectionArguments
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
}

kotlin {
    // 25, the current LTS. The toolchain is downloaded if the machine has no such JDK,
    // so this is the version everything compiles and runs against — locally, in CI and
    // in the image — rather than whatever happens to be on the PATH.
    jvmToolchain(25)

    compilerOptions {
        // Spring Framework 7 annotates nullability with JSpecify. Strict mode turns
        // those annotations into real Kotlin types instead of platform types, so a
        // nullable Java return cannot slip into a non-null Kotlin value unchecked.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// One Postgres for the whole build, shared by code generation and by every module's
// tests. Registered here because this plugin is applied everywhere; the container is
// started on first use and stopped when the build ends.
val migratedPostgres =
    gradle.sharedServices.registerIfAbsent("migratedPostgres", MigratedPostgresService::class) {
        parameters.rootDirectory.set(rootProject.layout.projectDirectory)
    }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Where to find that container. Passed at execution time, so `./gradlew help` does
    // not start a database, and absent when tests are run from an IDE — which is what
    // the fallback in `PostgresTestDatabase` is for.
    usesService(migratedPostgres)
    // `-Pbarometr.test.ownContainers=true` withholds it, and every module starts a
    // container of its own instead. Kept because it is the first thing to try when the
    // shared one is suspected — and because it is the same path an IDE run takes, so it
    // cannot rot unnoticed.
    if (!providers.gradleProperty("barometr.test.ownContainers").isPresent) {
        jvmArgumentProviders.add(
            objects.newInstance(PostgresConnectionArguments::class).apply {
                postgres.set(migratedPostgres)
            },
        )
    }
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

    // Gradle gives a test JVM 512 MB by default, which was enough until the application's
    // suite started booting a Spring context beside a Postgres, an Elasticsearch client
    // and several Testcontainers clients — with test classes running concurrently, that
    // ceiling arrives as an `OutOfMemoryError` in the middle of an unrelated test rather
    // than as anything that names the cause.
    maxHeapSize = "2g"

    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}
