package pl.barometr.testing

import liquibase.Contexts
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * One Postgres for the module's tests, and **one database per test class inside it**.
 *
 * The container is shared because it costs seconds to start and the schema under test
 * is never hand-written — a changeset that does not apply fails here rather than in
 * production. The databases are not shared, and that is what lets test classes run at
 * the same time: a class that clears a table in `@BeforeEach` is clearing its own copy,
 * so it cannot pull the ground from under a class running beside it.
 *
 * The copies are made with `CREATE DATABASE … TEMPLATE`, which Postgres does by copying
 * files — around seventy milliseconds, against the seconds that re-running twenty-seven
 * changesets per class would cost. The template is migrated once and **nothing ever
 * connects to it**, because Postgres refuses to copy a database that has a session open
 * on it.
 *
 * A module sees exactly the changelogs on its own test classpath, which is what lets
 * `ingestion` be tested without `sources` on it. That still holds: the template is
 * migrated inside the module's own test JVM.
 */
object PostgresTestDatabase {

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(TEMPLATE)
            .withUsername("test")
            .withPassword("test")
            .also { it.start() }
            .also(::migrate)
    }

    private val databases = ConcurrentHashMap<String, String>()
    private val counter = AtomicInteger()

    val username: String get() = container.username

    val password: String get() = container.password

    /**
     * The database for tests that start the whole application.
     *
     * One, shared by all of them, because Spring caches a context per set of properties
     * and a database each would mean starting the application once per test class. They
     * take a `@ResourceLock` instead so that no two of them run at once — the trade is
     * deliberate, and it is the opposite of the one below.
     */
    val jdbcUrl: String get() = urlOf(databaseNamed(APPLICATION))

    /** A migrated database of this class's own, made on first use and kept for its methods. */
    fun dslFor(owner: Class<*>): DSLContext =
        DSL.using(dataSourceOn(databaseNamed(owner.simpleName)), SQLDialect.POSTGRES)

    /**
     * The database the application under test is using, for the tests that assert on
     * what it wrote. A database of their own would be empty of exactly the rows they
     * are looking for.
     */
    fun applicationDsl(): DSLContext =
        DSL.using(dataSourceOn(databaseNamed(APPLICATION)), SQLDialect.POSTGRES)

    private fun databaseNamed(owner: String): String =
        databases.computeIfAbsent(owner) { name ->
            // Postgres allows 63 bytes and a test class name can be long, so the name is
            // truncated and a counter keeps it unique. It exists to be recognisable in
            // `\l` while debugging, not to be parsed.
            val database = "t${counter.incrementAndGet()}_${name.lowercase().take(48)}"
            clone(database)
            database
        }

    private fun clone(database: String) {
        DriverManager.getConnection(urlOf(TEMPLATE_ADMIN), container.username, container.password)
            .use { it.createStatement().execute("""CREATE DATABASE "$database" TEMPLATE $TEMPLATE""") }
    }

    private fun dataSourceOn(database: String): DataSource = PGSimpleDataSource().apply {
        setUrl(urlOf(database))
        user = container.username
        password = container.password
    }

    private fun urlOf(database: String): String =
        "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$database"

    private fun migrate(container: PostgreSQLContainer<*>) {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
            .use { connection ->
                val database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(JdbcConnection(connection))
                ClassLoaderResourceAccessor(javaClass.classLoader).use { accessor ->
                    // The same manifest the application runs. Contexts absent from
                    // this module's classpath are skipped, so a module gets its own
                    // schema and its dependencies' — never one hand-written for a test.
                    Liquibase(MASTER_CHANGELOG, accessor, database).update(Contexts())
                }
            }
    }

    private const val MASTER_CHANGELOG = "db/changelog/master.yaml"

    private const val APPLICATION = "application"

    /**
     * What a test starting the whole application holds while it runs.
     *
     * Those tests share one database on purpose — see [jdbcUrl] — so they are the one
     * group that cannot run beside each other. `@ResourceLock(APPLICATION_LOCK)` on
     * each of them is what says so, and it costs nothing: they also share a Spring
     * context, so only the first one pays for starting it.
     */
    const val APPLICATION_LOCK = "postgres.application"

    /** Migrated once, copied many times, and connected to by nothing else. */
    private const val TEMPLATE = "barometr_test"

    /**
     * `CREATE DATABASE` cannot run against the database being copied, so the statement
     * is issued from the one database Postgres always has.
     */
    private const val TEMPLATE_ADMIN = "postgres"
}
