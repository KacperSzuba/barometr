/**
 * What corpus publishes.
 *
 * Declared as a named interface because Spring Modulith treats a sub-package as
 * internal unless told otherwise: without this file every legitimate use of this
 * contract is reported as a boundary violation, and every illegitimate reach into
 * `internal` is not.
 *
 * A Java file in a Kotlin module because Kotlin has no package-level annotations.
 */
@org.springframework.modulith.NamedInterface("api")
package pl.barometr.corpus.api;
