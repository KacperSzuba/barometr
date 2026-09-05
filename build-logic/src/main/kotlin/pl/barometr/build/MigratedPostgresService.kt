package pl.barometr.build

import liquibase.Contexts
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.CompositeResourceAccessor
import liquibase.resource.DirectoryResourceAccessor
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.DriverManager

/**
 * One Postgres container per build, migrated before anyone reads it — and everything
 * that needs a database during the build reads it.
 *
 * This is what makes generated jOOQ code trustworthy: the classes describe a database
 * that the project's own changelog produced, on the same engine and version production
 * runs. A changeset that does not apply, or a column that disappeared, breaks
 * compilation rather than surfacing at runtime.
 *
 * The tests use it too, and that is where it pays for itself twice over. Nine modules
 * each starting their own container and re-running the same twenty-seven changesets was
 * most of what a test run spent its time on — and every one of those containers brought
 * a reaper of its own, which is how a build could finish leaving processes behind.
 *
 * A build service rather than a task, so the container is started at most once, shared
 * by every module, and stopped when the build ends rather than when a watchdog notices.
 */
abstract class MigratedPostgresService :
    BuildService<MigratedPostgresService.Params>,
    AutoCloseable {

    interface Params : BuildServiceParameters {
        /** Repository root; module resource roots are discovered underneath it. */
        val rootDirectory: DirectoryProperty

        /**
         * A Postgres that is already running, as `jdbc:postgresql://host:port`, or
         * empty to start one. See [BuildPostgres] for what sets it and why.
         */
        val existingServer: Property<String>
        val username: Property<String>
        val password: Property<String>
    }

    /**
     * The server this build talks to: one it started, or one it was pointed at.
     *
     * [own] is what gets stopped at the end of the build. A server somebody else is
     * running is theirs, and stopping it would be this build reaching outside itself.
     */
    private class Server(
        val baseUrl: String,
        val username: String,
        val password: String,
        val own: PostgreSQLContainer<*>?,
    )

    private val server: Server by lazy {
        val existing = parameters.existingServer.getOrElse("")

        if (existing.isBlank()) startedContainer() else adopted(existing)
    }

    /**
     * The ordinary path: a container of the image production runs, started for this
     * build and stopped with it.
     */
    private fun startedContainer(): Server {
        // pgvector image, because the schema declares `vector` columns and HNSW
        // indexes that plain Postgres cannot create.
        val container = PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(TEMPLATE)
            .withUsername("barometr")
            .withPassword("barometr")
            // A share of the machine rather than all of it: a build that makes the
            // laptop unusable while it runs is one people stop running. Half, because
            // this one database answers every module's tests at once — a tighter cap
            // was measured, and it doubled the suite.
            .withCreateContainerCmdModifier { it.hostConfig?.withNanoCPUs(CPU_SHARE) }
            // Every module's test classes hold a small pool each against this one
            // server. The default hundred runs out, and the failure lands in whichever
            // class was unlucky rather than in the one that took the connections.
            .withCommand("postgres", "-c", "max_connections=$MAX_CONNECTIONS")
            .also { it.start() }

        return Server(
            baseUrl = "jdbc:postgresql://${container.host}:${container.firstMappedPort}",
            username = container.username,
            password = container.password,
            own = container,
        ).also { migrate(it) }
    }

    /**
     * A server that is already running, which the build was told about.
     *
     * For a machine with no Docker daemon — a hosted agent, a locked-down laptop —
     * where the alternative is not "a different database" but "no build at all". It is
     * still Postgres with `pgvector`, still migrated by this project's own changelog,
     * so what the generated code and the tests see is unchanged; what differs is who
     * started the process. That is why this is a URL and never a dialect switch: the
     * rule that matters is the schema under test being the schema the migrations
     * produce, and an H2 would break it where this does not.
     *
     * The template is dropped and made again, because a database left by an earlier
     * build carries an earlier schema and would migrate onto it rather than from
     * nothing.
     */
    private fun adopted(baseUrl: String): Server {
        val server = Server(
            baseUrl = baseUrl.trimEnd('/'),
            username = parameters.username.getOrElse(ADMIN),
            password = parameters.password.getOrElse(ADMIN),
            own = null,
        )

        recreate(server, TEMPLATE)

        return server.also { migrate(it) }
    }

    /**
     * Made on first use rather than beside the container above, and that is not a
     * style choice: reading `container` from inside `container`'s own initialiser
     * re-enters it, and Kotlin's `lazy` answers a re-entrant call by running the
     * initialiser again. It started a container, which started a container, until
     * Docker had two hundred of them and stopped answering.
     */
    private val codegenDatabase: String by lazy { CODEGEN.also(::clone) }

    /**
     * The database code generation reads.
     *
     * A copy rather than the template itself, because Postgres refuses to copy a
     * database that has a session open on it — and generation holds one for as long as
     * it takes to read every table.
     */
    val jdbcUrl: String get() = urlOf(codegenDatabase)

    /**
     * The migrated database a test class copies for itself. Nothing connects to it,
     * which is the one condition `CREATE DATABASE … TEMPLATE` imposes.
     */
    val templateUrl: String get() = urlOf(TEMPLATE)

    val username: String get() = server.username

    val password: String get() = server.password

    /**
     * A copy of the migrated template, replacing whatever an earlier build left under
     * the same name — which cannot happen to a container and always happens to a
     * server that outlives the build.
     */
    private fun clone(database: String) {
        recreate(server, database, from = TEMPLATE)
    }

    private fun recreate(server: Server, database: String, from: String? = null) {
        val template = from?.let { " TEMPLATE \"$it\"" }.orEmpty()

        DriverManager.getConnection(urlOf(server, ADMIN), server.username, server.password).use { admin ->
            admin.createStatement().use { statement ->
                statement.execute("""DROP DATABASE IF EXISTS "$database"""")
                statement.execute("""CREATE DATABASE "$database"$template""")
            }
        }
    }

    /**
     * Takes the server rather than reading it, which is not a style choice: the
     * template is made from inside `server`'s own initialiser, and Kotlin's `lazy`
     * answers a re-entrant call by running the initialiser again. It recursed until the
     * stack ran out — the same trap [codegenDatabase] documents one field up.
     */
    private fun urlOf(server: Server, database: String) = "${server.baseUrl}/$database"

    private fun urlOf(database: String) = urlOf(server, database)

    private fun migrate(server: Server) {
        // Liquibase finds its own services — the MDC manager, the parsers, the
        // Postgres dialect — through the thread's context classloader. Inside a
        // Gradle build that is Gradle's, which has never heard of Liquibase, and the
        // failure is an NPE from `Scope.getMdcManager()` that says nothing about the
        // cause. Swapped for the duration of the migration and put back afterwards.
        val thread = Thread.currentThread()
        val callersClassLoader = thread.contextClassLoader
        thread.contextClassLoader = javaClass.classLoader
        try {
            runMigration(server)
        } finally {
            thread.contextClassLoader = callersClassLoader
        }
    }

    private fun runMigration(server: Server) {
        val roots = resourceRoots()
        check(roots.isNotEmpty()) { "No src/main/resources directories found under the repository root" }

        // Every module's resources as one search path, so `db/changelog/master.yaml`
        // in `app` can include a changelog that lives in `identity` — the same way
        // the classpath composes them at runtime, which is the point: the build must
        // migrate exactly what the application will.
        val accessor = CompositeResourceAccessor(roots.map { DirectoryResourceAccessor(it) })

        val dataSource = PGSimpleDataSource().apply {
            setUrl("${server.baseUrl}/$TEMPLATE")
            user = server.username
            password = server.password
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
        server.own?.takeIf { it.isRunning }?.stop()
    }

    private companion object {
        const val MASTER_CHANGELOG = "db/changelog/master.yaml"

        /** Migrated once, copied many times, connected to by nothing. */
        const val TEMPLATE = "barometr_template"

        const val CODEGEN = "barometr_codegen"

        /** `CREATE DATABASE` cannot run against the database being copied. */
        const val ADMIN = "postgres"

        /** Four cores, in the units Docker counts them: nanoseconds of CPU per second. */
        const val CPU_SHARE = 2_500_000_000L

        const val MAX_CONNECTIONS = 300
    }
}
