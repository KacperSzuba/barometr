package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/**
 * Turns a closed window into something a person reads.
 *
 * Plain and Polish, and deliberately not much: the designed templates are their own
 * task, and a placeholder that pretends to be one would be harder to replace than an
 * honest paragraph. What this does settle is the part templates cannot change — one
 * line per matter, the reason it was sent beside it, and the way out at the bottom.
 *
 * The reason is not a nicety. A digest whose entries do not say what caught them
 * cannot be acted on: the reader cannot tell a keyword that is too broad from an act
 * they genuinely watch, so they turn all of it off rather than the one line.
 */
@Component
class DigestMail {

    fun compose(contents: DigestContents, to: String, unsubscribeUrl: String): EmailMessage {
        val matters = contents.matters

        return EmailMessage(
            to = to,
            subject = subjectFor(matters.size),
            text = textOf(matters, unsubscribeUrl),
            html = htmlOf(matters, unsubscribeUrl),
            unsubscribeUrl = unsubscribeUrl,
        )
    }

    /**
     * The count is in the subject because that is the whole decision a reader makes in
     * the inbox: three things is worth opening now, one can wait.
     *
     * Polish counts in three cases, and the rule is not "two to four": 22 takes the same
     * form as 2 and 12 does not. Getting it wrong is the kind of thing that makes a
     * product read as machine-written, in the one line every reader sees.
     */
    private fun subjectFor(matters: Int): String = "Barometr: $matters ${caseFor(matters)}"

    private fun caseFor(count: Int): String {
        val last = count % 10
        val lastTwo = count % 100

        return when {
            count == 1 -> "sprawa"
            last in FEW && lastTwo !in TEENS -> "sprawy"
            else -> "spraw"
        }
    }

    private fun textOf(matters: List<DigestContents.Matter>, unsubscribeUrl: String): String =
        buildString {
            appendLine("Co się wydarzyło:")
            appendLine()
            matters.forEach { matter ->
                appendLine("* ${matter.title}")
                closingFor(matter)?.let { appendLine("  $it") }
                appendLine("  ${reasonFor(matter)}")
                appendLine()
            }
            appendLine("Nie chcesz tych wiadomości? $unsubscribeUrl")
        }

    private fun htmlOf(matters: List<DigestContents.Matter>, unsubscribeUrl: String): String =
        buildString {
            append("<h1>Co się wydarzyło</h1><ul>")
            matters.forEach { matter ->
                append("<li><strong>${escaped(matter.title)}</strong><br>")
                closingFor(matter)?.let { append("<strong>${escaped(it)}</strong><br>") }
                append("<small>${escaped(reasonFor(matter))}</small></li>")
            }
            append("</ul>")
            append("""<p><a href="${escaped(unsubscribeUrl)}">Zrezygnuj z tych wiadomości</a></p>""")
        }

    /** What the reader chose that caught this, in their own words. */
    private fun reasonFor(matter: DigestContents.Matter): String =
        matter.notifications.first().matchedBy.let { "Pasuje do: ${it.value}" }

    /**
     * The line that makes this e-mail worth opening the same day, and the only one here
     * that asks the reader to do something.
     *
     * Given in full above the matched interest rather than as "za trzy dni", because a
     * digest is read whenever the inbox is read: a countdown composed on Tuesday and
     * opened on Thursday would be wrong by exactly the amount that matters. The
     * earliest date wins when a matter carries more than one — a draft sent out for
     * comment twice is two windows, and the one closing first is the one still open.
     */
    private fun closingFor(matter: DigestContents.Matter): String? =
        matter.notifications.mapNotNull { it.closesOn }.minOrNull()
            ?.let { "Termin zgłaszania uwag: ${it.format(CLOSING_DATE)}" }

    /**
     * A title is somebody else's text — a register's — and it reaches this HTML
     * unescaped otherwise. An act called `<b>` is not a plausible attack, but the day
     * a title carries an ampersand the mail should still render.
     */
    private fun escaped(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        /**
         * Digits rather than a month name: `MMMM` in Polish is a declension trap — the
         * form a date takes is not the form a month is named in — and a deadline is the
         * last line in this mail that should read as machine-written.
         */
        val CLOSING_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        /** The final digit that takes the second case — 2, 3, 4, and so 22, 23, 24. */
        val FEW = 2..4

        /** Except in the teens, where 12, 13 and 14 take the third. */
        val TEENS = 12..14
    }
}
