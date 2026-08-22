package pl.barometr.build

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider

/**
 * Tells a test JVM where the build's Postgres is.
 *
 * An argument *provider* rather than `systemProperty(...)`, because the value is only
 * known once the container has started and starting it at configuration time would
 * mean a container for `./gradlew help`. This is read when the test task actually runs.
 *
 * A test JVM that receives nothing falls back to starting its own container, which is
 * what happens when tests are run from an IDE.
 */
abstract class PostgresConnectionArguments : CommandLineArgumentProvider {

    @get:Internal
    abstract val postgres: Property<MigratedPostgresService>

    override fun asArguments(): Iterable<String> = postgres.get().let { database ->
        listOf(
            "-D$TEMPLATE_URL=${database.templateUrl}",
            "-D$USERNAME=${database.username}",
            "-D$PASSWORD=${database.password}",
        )
    }

    companion object {
        const val TEMPLATE_URL = "barometr.test.postgres.template-url"
        const val USERNAME = "barometr.test.postgres.username"
        const val PASSWORD = "barometr.test.postgres.password"
    }
}
