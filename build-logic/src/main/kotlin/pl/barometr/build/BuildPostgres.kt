package pl.barometr.build

import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Registers the one Postgres this build talks to, and settles where it comes from.
 *
 * Registered from two plugins — the one every module applies and the one that generates
 * jOOQ sources — and `registerIfAbsent` means whichever runs first decides the
 * parameters. So the registration lives here rather than being written out twice: two
 * copies would be two answers to "which server" the day one of them was edited.
 *
 * **By default the build starts its own container**, which is the arrangement the
 * project is built around: the image production runs, migrated by this project's own
 * changelog, thrown away at the end.
 *
 * **Pointed at a server that is already running, it uses that instead.** For a machine
 * with no Docker daemon, where the alternative is not a different database but no build
 * at all:
 *
 * ```
 * ./gradlew check -Pbarometr.postgres.url=jdbc:postgresql://localhost:5432
 * BAROMETR_POSTGRES_URL=jdbc:postgresql://localhost:5432 ./gradlew check
 * ```
 *
 * It has to be Postgres with `pgvector` — the schema declares `vector` columns — and it
 * is migrated from nothing by the same changelog, so the schema under test is the schema
 * the migrations produce either way. That is the rule this must not break; who started
 * the process is not part of it.
 */
object BuildPostgres {

    const val URL_PROPERTY = "barometr.postgres.url"
    const val USERNAME_PROPERTY = "barometr.postgres.username"
    const val PASSWORD_PROPERTY = "barometr.postgres.password"

    private const val URL_VARIABLE = "BAROMETR_POSTGRES_URL"
    private const val USERNAME_VARIABLE = "BAROMETR_POSTGRES_USERNAME"
    private const val PASSWORD_VARIABLE = "BAROMETR_POSTGRES_PASSWORD"

    /** What `postgres:16` and every managed Postgres call the superuser. */
    private const val DEFAULT_ROLE = "postgres"

    fun registerIn(project: Project): Provider<MigratedPostgresService> =
        project.gradle.sharedServices.registerIfAbsent(
            "migratedPostgres",
            MigratedPostgresService::class.java,
        ) {
            parameters.rootDirectory.set(project.rootProject.layout.projectDirectory)
            parameters.existingServer.set(setting(project, URL_PROPERTY, URL_VARIABLE, ""))
            parameters.username.set(setting(project, USERNAME_PROPERTY, USERNAME_VARIABLE, DEFAULT_ROLE))
            parameters.password.set(setting(project, PASSWORD_PROPERTY, PASSWORD_VARIABLE, DEFAULT_ROLE))
        }

    /**
     * A Gradle property, an environment variable, or the default — in that order.
     *
     * Both, because the two are used by different people: a property is what somebody
     * types on a command line, and a variable is what an agent has set for every build
     * it runs without editing anybody's Gradle file.
     */
    private fun setting(project: Project, property: String, variable: String, fallback: String): Provider<String> =
        project.providers.gradleProperty(property)
            .orElse(project.providers.environmentVariable(variable))
            .orElse(fallback)
}
