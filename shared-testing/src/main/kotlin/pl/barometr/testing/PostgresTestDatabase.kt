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
import javax.sql.DataSource

/**
 * One Postgres for the module's tests, migrated by the application's own changelog.
 *
 * Shared rather than started per test class: the container costs seconds, and the
 * schema under test is never hand-written — a changeset that does not apply fails
 * the tests here rather than in production.
 *
 * A module sees exactly the changelogs on its own test classpath, which is what
 * lets `ingestion` be tested without `sources` on it.
 */
object PostgresTestDatabase {

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName("barometr_test")
            .withUsername("test")
            .withPassword("test")
            .also { it.start() }
            .also(::migrate)
    }

    val jdbcUrl: String get() = container.jdbcUrl

    val username: String get() = container.username

    val password: String get() = container.password

    val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl)
            user = container.username
            password = container.password
        }
    }

    fun dsl(): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

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
}
