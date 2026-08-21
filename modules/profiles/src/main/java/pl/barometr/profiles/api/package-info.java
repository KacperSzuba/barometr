/**
 * What profiles publishes.
 *
 * One question, asked in one direction: does anybody care about this thing. The answer
 * comes with the profile, its version and the interest that caught it, because whatever
 * sends the notification has to be able to say why it was sent — and a year later, why
 * it was sent then.
 *
 * What is deliberately not here: the interests themselves, the editing, the versions.
 * A context that could read somebody's profile could also decide for itself what it
 * means to match one, and then there would be two answers to the only question this
 * context exists to answer.
 *
 * A Java file in a Kotlin module because Kotlin has no package-level annotations.
 */
@org.springframework.modulith.NamedInterface("api")
package pl.barometr.profiles.api;
