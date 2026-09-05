package pl.barometr.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target

/**
 * Generates jOOQ sources for **one** schema — the one its module owns.
 *
 * Generating everything into a shared module would be less work and would also
 * quietly undo the architecture: any module could then reference any table, and
 * the boundaries enforced in Gradle would stop at the database. A module can
 * only name the tables it is responsible for.
 */
@CacheableTask
abstract class GenerateJooqSources : DefaultTask() {

    @get:Internal
    abstract val postgres: Property<MigratedPostgresService>

    @get:Input
    abstract val schemaName: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    /**
     * Every changelog in the repository, not just this context's: a foreign key
     * added elsewhere changes what this schema looks like, and the generated
     * code has to be rebuilt when it does.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val migrations: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val database = postgres.get()
        outputDirectory.get().asFile.deleteRecursively()

        val configuration = Configuration()
            .withJdbc(
                Jdbc()
                    .withDriver("org.postgresql.Driver")
                    .withUrl(database.jdbcUrl)
                    .withUser(database.username)
                    .withPassword(database.password),
            )
            .withGenerator(
                Generator()
                    .withName("org.jooq.codegen.KotlinGenerator")
                    .withDatabase(
                        Database()
                            .withName("org.jooq.meta.postgres.PostgresDatabase")
                            .withInputSchema(schemaName.get())
                            .withIncludes(".*")
                            // Liquibase's bookkeeping is not part of any context's model.
                            .withExcludes("databasechangelog|databasechangeloglock")
                            .withForcedTypes(
                                // `tstzrange` carries the valid-time axis of the
                                // legislative model. Left unmapped it generates as
                                // `Any?`, which would mean writing range predicates
                                // as raw SQL strings — no type checking on the one
                                // part of the schema that most needs it.
                                ForcedType()
                                    .withUserType("org.jooq.postgres.extensions.types.OffsetDateTimeRange")
                                    .withBinding("org.jooq.postgres.extensions.bindings.OffsetDateTimeRangeBinding")
                                    .withIncludeTypes("tstzrange"),
                                // `inet` holds the address a session signed in from.
                                // Unmapped it generates as `Any?`, which is a column
                                // nothing can read without a cast and nothing can write
                                // without a raw SQL fragment.
                                ForcedType()
                                    .withUserType("org.jooq.postgres.extensions.types.Inet")
                                    .withBinding("org.jooq.postgres.extensions.bindings.InetBinding")
                                    .withIncludeTypes("inet"),
                            ),
                    )
                    .withGenerate(
                        Generate()
                            .withDeprecated(false)
                            // Records only. POJOs and DAOs would push a second,
                            // competing model into modules that already have their
                            // own domain types.
                            .withPojos(false)
                            .withDaos(false)
                            .withJavaTimeTypes(true)
                            .withKotlinNotNullRecordAttributes(true),
                    )
                    .withTarget(
                        Target()
                            .withPackageName(packageName.get())
                            .withDirectory(outputDirectory.get().asFile.absolutePath)
                            .withClean(true),
                    ),
            )

        GenerationTool.generate(configuration)
    }
}
