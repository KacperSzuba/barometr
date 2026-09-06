package pl.barometr.testing

import liquibase.Contexts
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.jooq.DSLContext
import org.jooq.SQLDialect
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * One Postgres for the whole build, and **one database per test class inside it**.
 *
 * Two decisions, and they pull in opposite directions on purpose.
 *
 * **One container.** It is started by the build, before any module's tests, and stopped
 * when the build ends. Nine modules each starting their own and re-running the same
 * twenty-seven changesets was most of what a test run spent its time on, and every one
 * of those containers brought a reaper of its own. The schema in it is still the one
 * the project's changelog produces on the engine production runs — a changeset that
 * does not apply fails the build rather than surfacing later.
 *
 * **A database each.** Test classes run at the same time, so a class clearing a table in
 * `@BeforeEach` has to be clearing its own copy. The copies are made with
 * `CREATE DATABASE … TEMPLATE`, which Postgres does by copying files — around seventy
 * milliseconds. Nothing ever connects to the template, which is the one condition that
 * imposes.
 *
 * Run from an IDE there is no build to hand a container over, so one is started here
 * and migrated from this module's own classpath instead. The tests cannot tell the
 * difference, and neither path leaves a container behind.
 */
object PostgresTestDatabase {

    /**
     * Where the migrated template lives.
     *
     * The build hands this over: one container for the whole run, started before any
     * module's tests and stopped when the build ends. Running from an IDE there is no
     * build to hand anything over, so a container is started here instead and migrated
     * the same way — the tests cannot tell the difference, and neither path leaves one
     * behind.
     */
    private val template: Template by lazy {
        System.getProperty(TEMPLATE_URL)?.let {
            Template(it, System.getProperty(USERNAME), System.getProperty(PASSWORD))
        } ?: ownContainer()
    }

    private fun ownContainer(): Template =
        PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(TEMPLATE)
            .withUsername("test")
            .withPassword("test")
            // A share of the machine rather than all of it: a suite that makes the
            // laptop unusable while it runs is one people stop running.
            .withCreateContainerCmdModifier { it.hostConfig?.withNanoCPUs(CPU_SHARE) }
            .withCommand("postgres", "-c", "max_connections=$MAX_CONNECTIONS")
            .also { it.start() }
            .also(::migrate)
            .let { Template(it.jdbcUrl, it.username, it.password) }

    private class Template(url: String, val username: String, val password: String) {

        // Testcontainers hands back a URL with parameters on it — `?loggerLevel=OFF` —
        // and reading the database name off the end of that produced a template called
        // `barometr_test?loggerLevel=OFF`. Everything here is derived from the address
        // alone.
        private val address = url.substringBefore("?")

        /** What a copy is made from. */
        val database: String = address.substringAfterLast("/")

        private val server: String = address.substringBeforeLast("/")

        /** The same server, a different database on it. */
        fun urlOf(database: String): String = "$server/$database"
    }

    private val databases = ConcurrentHashMap<String, String>()

    /**
     * One pool per database, not per call.
     *
     * JUnit builds a fresh test instance for every method, so `dslFor` is called once
     * per test — and a pool built each time is a pool leaked each time. A hundred and
     * fifty of them exhausted the server's connection limit before the suite was half
     * done.
     */
    private val pools = ConcurrentHashMap<String, DataSource>()
    private val counter = AtomicInteger()

    val username: String get() = template.username

    val password: String get() = template.password

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

    /**
     * A copy of the template, replacing whatever is there under the same name.
     *
     * The drop is for a server that outlives the build — one this suite was pointed at
     * rather than one it started. A container comes up empty every time and the drop is
     * a no-op; an adopted server still holds last run's copies, and the counter in the
     * name starts again at one, so the second run collided with the first.
     */
    private fun clone(database: String) {
        DriverManager.getConnection(urlOf(ADMIN), template.username, template.password)
            .use {
                it.createStatement().execute("""DROP DATABASE IF EXISTS "$database"""")
                it.createStatement()
                    .execute("""CREATE DATABASE "$database" TEMPLATE "${template.database}"""")
            }
    }

    /**
     * Pooled, and that is most of what makes a test suite fast.
     *
     * jOOQ asks the `DataSource` for a connection per statement. An unpooled one
     * answers by opening a TCP connection and authenticating, five to twenty
     * milliseconds each — so a test that writes two thousand rows spends its life in
     * handshakes rather than in the database. Small pools, because a test class uses
     * one thread and the one that does not uses four.
     */
    private fun dataSourceOn(database: String): DataSource =
        pools.computeIfAbsent(database) {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = urlOf(database)
                    username = template.username
                    password = template.password
                    maximumPoolSize = POOL_SIZE
                    poolName = database
                },
            )
        }

    private fun urlOf(database: String): String = template.urlOf(database)

    /** Only for the container this object started itself; the build migrates its own. */
    private fun migrate(container: PostgreSQLContainer<*>) {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
            .use { connection ->
                val database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(JdbcConnection(connection))
                ClassLoaderResourceAccessor(javaClass.classLoader).use { accessor ->
                    // The same manifest the application runs. Contexts absent from
                    // this module's classpath are skipped, so a module run on its own
                    // gets its own schema and its dependencies' — never one
                    // hand-written for a test.
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
    private const val ADMIN = "postgres"

    /** Two cores, in the units Docker counts them: nanoseconds of CPU per second. */
    private const val CPU_SHARE = 2_000_000_000L

    /**
     * Enough for the one test that claims jobs from four threads at once, and small
     * enough that a dozen test classes running together do not open a hundred
     * connections between them.
     */
    private const val POOL_SIZE = 4

    /**
     * The default hundred is not enough for a dozen test classes holding a small pool
     * each, and the failure it produces — `sorry, too many clients already` — arrives
     * in whichever class was unlucky rather than in the one that took the connections.
     */
    private const val MAX_CONNECTIONS = 300

    // Set by the build when it has a container to share. Named here rather than
    // imported, because `shared-testing` is not on the build's classpath.
    private const val TEMPLATE_URL = "barometr.test.postgres.template-url"
    private const val USERNAME = "barometr.test.postgres.username"
    private const val PASSWORD = "barometr.test.postgres.password"
}
