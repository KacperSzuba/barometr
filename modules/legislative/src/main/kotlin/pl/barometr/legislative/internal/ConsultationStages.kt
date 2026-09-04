package pl.barometr.legislative.internal

import java.util.Locale

/**
 * Which of a card's stages is the one the public may write into.
 *
 * A government draft passes through eight stages and comments are invited in three of
 * them, but only one of the three invites *anybody's*. "Uzgodnienia" is the ministries
 * agreeing among themselves and "Opiniowanie" is a named list of institutions being
 * asked; a citizen who files under either has filed into a process they were not party
 * to. This product's promise is "you have until the fifteenth to write in", so it says
 * so for the stage where that is true and stays quiet about the other two.
 *
 * Matched on the label RPL prints rather than on a stage code, because the card has no
 * codes. Ministries name the stage variously — "Konsultacje publiczne", "Konsultacje
 * publiczne i opiniowanie" — so the phrase is looked for inside the label rather than
 * required to be all of it, with the whitespace RPL indents with normalised away.
 */
object ConsultationStages {

    fun isPublicConsultation(stageName: String): Boolean = PUBLIC_CONSULTATION in normalise(stageName)

    private fun normalise(stageName: String): String =
        stageName.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private const val PUBLIC_CONSULTATION = "konsultacje publiczne"

    /** `\s` alone leaves the non-breaking spaces RPL lays its labels out with. */
    private val WHITESPACE = Regex("""[\s\u00A0]+""")
}
