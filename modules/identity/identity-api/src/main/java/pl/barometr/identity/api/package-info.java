/**
 * The published contract of the identity module.
 *
 * Declared as a named interface so Spring Modulith treats this package as public
 * while everything under {@code pl.barometr.identity.internal} stays closed.
 * A Java file because {@code @NamedInterface} is read from {@code package-info},
 * which Kotlin has no equivalent of.
 */
@org.springframework.modulith.NamedInterface("api")
package pl.barometr.identity.api;
