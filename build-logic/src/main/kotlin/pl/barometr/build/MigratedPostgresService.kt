package pl.barometr.build

import liquibase.Contexts
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.CompositeResourceAccessor
import liquibase.resource.DirectoryResourceAccessor
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import org.postgresql.ds.PGSimpleDataSource

/**
 * One Postgres container per build, migrated before anyone reads it.
 *
 * This is what makes generated jOOQ code trustworthy: the classes describe a
 * database that the project's own changelog produced, on the same engine and
 * version production runs. A changeset that does not apply, or a column that
 * disappeared, breaks compilation rather than surfacing at runtime.
 *
 * A build service rather than a task so the container is started at most once
 * and shut down when the build ends, however many modules generate code.
 */
abstract class MigratedPostgresService :
    BuildService<MigratedPostgresService.Params>,
    AutoCloseable {

    interface Params : BuildServiceParameters {
        /** Repository root; module resource roots are discovered underneath it. */
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
        // Liquibase finds its own services — the MDC manager, the parsers, the
        // Postgres dialect — through the thread's context classloader. Inside a
        // Gradle build that is Gradle's, which has never heard of Liquibase, and the
        // failure is an NPE from `Scope.getMdcManager()` that says nothing about the
        // cause. Swapped for the duration of the migration and put back afterwards.
        val thread = Thread.currentThread()
        val callersClassLoader = thread.contextClassLoader
        thread.contextClassLoader = javaClass.classLoader
        try {
            runMigration(container)
        } finally {
            thread.contextClassLoader = callersClassLoader
        }
    }

    private fun runMigration(container: PostgreSQLContainer<*>) {
        val roots = resourceRoots()
        check(roots.isNotEmpty()) { "No src/main/resources directories found under the repository root" }

        // Every module's resources as one search path, so `db/changelog/master.yaml`
        // in `app` can include a changelog that lives in `identity` — the same way
        // the classpath composes them at runtime, which is the point: the build must
        // migrate exactly what the application will.
        val accessor = CompositeResourceAccessor(roots.map { DirectoryResourceAccessor(it) })

        val dataSource = PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl)
            user = container.username
            password = container.password
        }

        dataSource.connection
            .use { connection ->
                val database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(JdbcConnection(connection))
                accessor.use {
                    // No contexts: the build generates code from the schema every
                    // environment shares, never from a fixture one of them adds.
                    Liquibase(MASTER_CHANGELOG, it, database).update(Contexts())
                }
            }
    }

    /**
     * Discovered rather than listed, so adding a module needs no build change — and
     * so codegen sees the same resources the application will.
     */
    private fun resourceRoots(): List<File> =
        parameters.rootDirectory.get().asFile
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.isDirectory && it.invariantSeparatorsPath.endsWith("src/main/resources") }
            .toList()

    override fun close() {
        if (container.isRunning) container.stop()
    }

    private companion object {
        const val MASTER_CHANGELOG = "db/changelog/master.yaml"
    }
}
