package pl.barometr.alerts.internal

/**
 * What a significance reason says, in Polish, to somebody reading their mail.
 *
 * [SignificanceReason] deliberately holds codes and no sentences, because the language
 * a reason is rendered in belongs to whatever is rendering it. An e-mail is one of
 * those things — it is a frontend that happens to arrive in an inbox — so this is where
 * its wording lives, once, for the plain part and the HTML part alike.
 *
 * Short and factual on purpose. The line has to be readable beside a title in a
 * crowded inbox, and a reason that needs a clause to explain itself is one the reader
 * skips along with the item it was meant to justify.
 */
object SignificanceWording {

    fun of(reason: SignificanceReason): String = when (reason) {
        SignificanceReason.IN_FORCE -> "Weszło w życie"
        SignificanceReason.NEARING_ENACTMENT -> "Blisko uchwalenia"
        SignificanceReason.DEADLINE_IMMINENT -> "Termin w tym tygodniu"
        SignificanceReason.DEADLINE_APPROACHING -> "Termin w tym miesiącu"
        SignificanceReason.DEADLINE_AHEAD -> "Termin wyznaczony"
    }
}
