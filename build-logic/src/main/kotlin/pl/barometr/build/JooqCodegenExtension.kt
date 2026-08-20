package pl.barometr.build

import org.gradle.api.provider.Property

/**
 * How a module declares which database schema it owns.
 *
 * One schema per module, and a module generates code for no other.
 */
interface JooqCodegenExtension {
    val schema: Property<String>
}
