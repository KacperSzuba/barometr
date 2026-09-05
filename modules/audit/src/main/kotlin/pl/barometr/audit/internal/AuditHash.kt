package pl.barometr.audit.internal

import pl.barometr.audit.api.AuditableAttempt
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * The link in the chain: each entry's hash covers the one before it.
 *
 * Changing a recorded entry changes its hash, which the next entry no longer matches,
 * and so on to the end — so a row cannot be quietly rewritten even by somebody who can
 * write to this table. That is the property the trigger cannot give: the trigger stops
 * the application, and this makes tampering by anybody else *visible*.
 *
 * **A field that was not there is not hashed as empty.** `detail` arrived after entries
 * had been written, and appending it as an empty string would have changed the material
 * of every one of them — a chain that no longer verifies its own history is a chain
 * nobody can use to argue anything.
 *
 * **The fields are joined by a separator none of them can contain.** Without one,
 * `POST` on `/a/b` and `POS` on `T/a/b` would hash identically, and a chain two
 * different histories can satisfy is evidence of neither.
 */
object AuditHash {

    fun of(previous: String?, at: Instant, attempt: AuditableAttempt): String {
        val material = listOf(
            previous.orEmpty(),
            at.toEpochMilli().toString(),
            attempt.actor?.value?.toString().orEmpty(),
            attempt.actorLabel.orEmpty(),
            attempt.action,
            attempt.resource,
            attempt.outcome.wireName,
            attempt.status?.toString().orEmpty(),
            attempt.peer.orEmpty(),
            // Appended only when there is one, so an entry written before the column
            // existed hashes exactly as it did and the chain over the whole table still
            // verifies. Nothing can be smuggled between fields to exploit the two
            // shapes: none of them may contain the separator.
        ).plus(listOfNotNull(attempt.detail)).joinToString(SEPARATOR)

        return HexFormat.of().formatHex(
            MessageDigest.getInstance(ALGORITHM).digest(material.toByteArray(Charsets.UTF_8)),
        )
    }

    private const val ALGORITHM = "SHA-256"

    /**
     * A newline, which none of these fields can hold: a method, a URL path, an e-mail
     * and a status are all single-line by construction, and any printable separator is
     * one somebody could put inside a value on purpose.
     */
    private const val SEPARATOR = "\n"
}
