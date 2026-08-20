package pl.barometr.build

import org.flywaydb.core.Flyway
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File

/**
 * One Postgres container per build, migrated by Flyway before anyone reads it.
 *
 * This is what makes generated jOOQ code trustworthy: the classes describe a
 * database that the project's own migrations produced, on the same engine and
 * version production runs. A migration that does not apply, or a column that
 * disappeared, breaks compilation rather than surfacing at runtime.
 *
 * A build service rather than a task so the container is started at most once
 * and shut down when the build ends, however many modules generate code.
 */
abstract class MigratedPostgresService :
    BuildService<MigratedPostgresService.Params>,
    AutoCloseable {

    interface Params : BuildServiceParameters {
        /** Repository root; migration directories are discovered underneath it. */
        val rootDirectory: DirectoryProperty
    }

    private val container: PostgreSQLContainer<*> by lazy {
        // pgvector image, because the schema declares `vector` columns and HNSW
        // indexes that plain Postgres cannot create.
        PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName("barometr_codegen")
            .withUsername("codegen")
            .withPassword("codegen")
            .also { it.start() }
            .also { migrate(it) }
    }

    val jdbcUrl: String get() = container.jdbcUrl
    val username: String get() = container.username
    val password: String get() = container.password

    private fun migrate(container: PostgreSQLContainer<*>) {
        val locations = migrationDirectories().map { "filesystem:${it.absolutePath}" }
        check(locations.isNotEmpty()) { "No db/migration directories found under the repository root" }

        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations(*locations.toTypedArray())
            .load()
            .migrate()
    }

    /**
     * Every module's `src/main/resources/db/migration`. Discovered rather than
     * listed, so adding a module needs no build change — and so codegen sees the
     * same set of migrations the application will.
     */
    private fun migrationDirectories(): List<File> =
        parameters.rootDirectory.get().asFile
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.isDirectory && it.invariantSeparatorsPath.endsWith("src/main/resources/db/migration") }
            .toList()

    override fun close() {
        if (container.isRunning) container.stop()
    }
}
