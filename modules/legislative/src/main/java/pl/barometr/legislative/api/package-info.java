/**
 * What legislative publishes.
 *
 * Declared as a named interface because Spring Modulith treats a sub-package as
 * internal unless told otherwise. Empty of behaviour today on purpose: nothing else
 * needs to ask this context anything yet, and a port published before it has a caller
 * is a guess about what that caller will want.
 *
 * A Java file in a Kotlin module because Kotlin has no package-level annotations.
 */
@org.springframework.modulith.NamedInterface("api")
package pl.barometr.legislative.api;
