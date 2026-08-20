package pl.barometr.testing

import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * One Postgres for the module's tests, migrated by the application's own Flyway
 * scripts.
 *
 * Shared rather than started per test class: the container costs seconds, and the
 * schema under test is never hand-written — a migration that does not apply fails
 * the tests here rather than in production.
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
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
