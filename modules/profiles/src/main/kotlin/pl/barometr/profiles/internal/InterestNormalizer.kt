package pl.barometr.profiles.internal

import org.springframework.stereotype.Component
import pl.barometr.profiles.api.InterestKind
import pl.barometr.shared.PkdCode
import pl.barometr.shared.Eli
import java.util.UUID

/**
 * Reads what somebody typed into the vocabulary of its kind, or refuses it.
 *
 * Normalising before storing is not cosmetic here: `(profile_id, version, kind, value)`
 * is the primary key and the matching index, so `62.01.z` and `62.01.Z` stored as
 * typed would be two interests that match different things while looking identical in
 * a list.
 */
@Component
class InterestNormalizer {

    fun normalize(interest: Interest): Interest {
        val raw = interest.value.trim()
        val value = when (interest.kind) {
            InterestKind.PKD -> PkdCode.parseOrNull(raw)?.value
            InterestKind.REGION -> TerytCode.parseOrNull(raw)?.value
            InterestKind.ACT -> Eli.parseOrNull(raw.uppercase())?.value
            // A draft has no single natural key — the same government bill is
            // `RM-0610-102-23` in one register, `UD383` in another and print `424` in
            // the Sejm — so a profile holds the identity the tracker assigned it, and
            // the three quotable numbers stay where they belong, on the draft.
            InterestKind.DRAFT -> raw.lowercase().takeIf { isUuid(it) }
            InterestKind.KEYWORD -> normalizeKeyword(raw)
        } ?: throw InvalidInterestException(interest.kind.wireName, interest.value)

        return Interest(interest.kind, value, interest.excluded)
    }

    /**
     * Case and inner spacing are flattened because the index treats them as the same
     * word anyway; the length floor is what stops a profile from subscribing to
     * everything by accident, which is a subscription nobody reads twice.
     */
    private fun normalizeKeyword(raw: String): String? =
        raw.lowercase()
            .replace(WHITESPACE, " ")
            .takeIf { it.length >= MIN_KEYWORD_LENGTH }

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value) }.isSuccess

    private companion object {
        const val MIN_KEYWORD_LENGTH = 3
        val WHITESPACE = Regex("\\s+")
    }
}
