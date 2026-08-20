/**
 * What identity publishes.
 *
 * Declared as a named interface because the build no longer draws this line: until
 * each context became a single Gradle module, `identity-api` was a separate project and
 * the compiler refused anything else. Now Spring Modulith is what says which package
 * other contexts may see, and it treats a sub-package as internal unless told
 * otherwise — so without this file every legitimate use of this contract is reported
 * as a boundary violation, and every illegitimate use of `internal` is not.
 *
 * A Java file in a Kotlin module because Kotlin has no package-level annotations.
 */
@org.springframework.modulith.NamedInterface("api")
package pl.barometr.identity.api;
