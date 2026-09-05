package pl.barometr.alerts.internal

import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns a closed window into something a person reads.
 *
 * **What this settles is the order of a reader's questions**, and the layout follows
 * it: is this worth opening (the subject and the line the inbox previews), what
 * happened (one heading per matter), is anything due (the deadline, in full, above
 * everything else about that matter), why am I being told (the interest that caught
 * it), and how do I stop (the bottom of every message).
 *
 * **Two bodies, and the plain one is not a fallback.** A message with only HTML scores
 * worse with every spam filter there is, and these alerts are exactly the kind that
 * must not land in a junk folder — so the text part carries the same lines in the same
 * order, not a summary of them.
 *
 * **The HTML is written the way e-mail is written, not the way a page is.** A table for
 * layout, styles on the elements themselves, one column six hundred pixels wide, no
 * image and no web font: Outlook renders a fraction of CSS, Gmail discards anything in
 * `<head>`, and a digest that arrives as a stack of unstyled text has failed for a
 * reason nobody can see from a browser. `color-scheme` is declared so that a client
 * inverting the message for dark mode inverts what was designed rather than guessing.
 *
 * **Nothing here links to a matter**, and that is a deliberate gap rather than an
 * oversight: the route to an act inside the web application belongs to the web
 * application, and a mail full of links this system guessed at is worse than one
 * without them. So each entry carries what it needs to stand on its own.
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

    /**
     * The line an inbox prints after the subject, which is the second and last thing a
     * reader sees before deciding.
     *
     * The most significant matter, because that is the one the digest was ordered to
     * put first; without this the preview is whatever text happens to come first in the
     * message, which in a styled mail is usually nothing at all.
     */
    private fun preheaderOf(matters: List<DigestContents.Matter>): String =
        matters.firstOrNull()?.let { first ->
            listOfNotNull(first.title, closingFor(first)).joinToString(" · ")
        }.orEmpty()

    private fun textOf(matters: List<DigestContents.Matter>, unsubscribeUrl: String): String =
        buildString {
            appendLine("Co się wydarzyło:")
            appendLine()
            matters.forEach { matter ->
                appendLine("* ${matter.title}")
                closingFor(matter)?.let { appendLine("  $it") }
                significanceOf(matter)?.let { appendLine("  $it") }
                appendLine("  ${reasonFor(matter)}")
                appendLine("  ${eventsIn(matter)}")
                appendLine()
            }
            appendLine("Nie chcesz tych wiadomości? $unsubscribeUrl")
        }

    /**
     * Built line by line rather than as one indented template.
     *
     * A raw string with `trimIndent` looks like the right tool and is not: the trim
     * happens after interpolation, so a multi-line piece spliced in at indent zero
     * takes the common indent to zero and leaves every literal line indented as it was
     * written. The document would arrive wrapped in eight spaces a browser forgives and
     * a mail client does not.
     */
    private fun htmlOf(matters: List<DigestContents.Matter>, unsubscribeUrl: String): String = buildString {
        appendLine("<!doctype html>")
        appendLine("""<html lang="pl">""")
        appendLine("<head>")
        appendLine("""<meta charset="utf-8">""")
        appendLine("""<meta name="viewport" content="width=device-width">""")
        appendLine("""<meta name="color-scheme" content="light dark">""")
        appendLine("""<meta name="supported-color-schemes" content="light dark">""")
        appendLine("<title>Barometr</title>")
        appendLine("</head>")
        appendLine("""<body style="margin:0;padding:0;background:$PAGE;">""")
        // The preview line, kept out of the rendered message: an inbox reads it, a
        // reader never sees it twice.
        appendLine(
            """<div style="display:none;max-height:0;overflow:hidden;opacity:0;">""" +
                "${escaped(preheaderOf(matters))}</div>",
        )
        appendLine(
            """<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" """ +
                """style="background:$PAGE;padding:24px 12px;"><tr><td align="center">""",
        )
        appendLine(
            """<table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" """ +
                """style="max-width:600px;width:100%;background:$CARD;border-radius:8px;font-family:$FONT;">""",
        )
        appendLine(
            """<tr><td style="padding:28px 28px 8px 28px;">""" +
                """<h1 style="margin:0;font-size:20px;line-height:28px;color:$INK;">""" +
                "Co się wydarzyło</h1></td></tr>",
        )
        matters.forEach { appendLine(matterIn(it)) }
        appendLine(
            """<tr><td style="padding:8px 28px 28px 28px;border-top:1px solid $RULE;">""" +
                """<p style="margin:16px 0 0 0;font-size:13px;line-height:20px;color:$QUIET;">""" +
                "Dostajesz tę wiadomość, bo obserwujesz te sprawy w Barometrze.<br>" +
                """<a href="${escaped(unsubscribeUrl)}" style="color:$QUIET;">""" +
                "Zrezygnuj z tych wiadomości</a></p></td></tr>",
        )
        appendLine("</table></td></tr></table>")
        appendLine("</body>")
        append("</html>")
    }

    /**
     * One matter, in the order a reader asks: what it is, when it is due, why it is near
     * the top, what caught it, and how much of it there was.
     *
     * The deadline is styled as the only emphatic thing in the block. It is the one line
     * in this message that expires.
     */
    private fun matterIn(matter: DigestContents.Matter): String = buildString {
        append("""<tr><td style="padding:16px 28px;border-top:1px solid $RULE;">""")
        append("""<p style="margin:0;font-size:16px;line-height:24px;font-weight:600;color:$INK;">""")
        append(escaped(matter.title))
        append("</p>")
        closingFor(matter)?.let { append(deadlineIn(it)) }
        significanceOf(matter)?.let { append(noteIn(it)) }
        append(noteIn(reasonFor(matter)))
        append(noteIn(eventsIn(matter)))
        append("</td></tr>")
    }

    private fun deadlineIn(closing: String): String =
        """<p style="margin:8px 0 0 0;font-size:14px;line-height:20px;font-weight:600;color:$URGENT;">""" +
            "${escaped(closing)}</p>"

    private fun noteIn(text: String): String =
        """<p style="margin:6px 0 0 0;font-size:13px;line-height:20px;color:$QUIET;">""" +
            "${escaped(text)}</p>"

    /** What the reader chose that caught this, in their own words. */
    private fun reasonFor(matter: DigestContents.Matter): String =
        matter.notifications.first().matchedBy.let { "Pasuje do: ${it.value}" }

    /**
     * Why this matter is where it is in the list.
     *
     * The digest orders by significance and, until now, showed nothing of it — so the
     * first entry looked arbitrary to anybody whose own reading of the week differed.
     * The reasons are the ranking's own words for itself; where it has none, the line is
     * left out rather than filled with a phrase that says nothing.
     */
    private fun significanceOf(matter: DigestContents.Matter): String? =
        matter.notifications
            .flatMap { it.significance.reasons }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ", transform = SignificanceWording::of)

    /**
     * How much happened, and when it last did.
     *
     * A matter is several notifications grouped into one entry, and without this the
     * grouping quietly loses that: four things happening to one bill in a week is a
     * different week from one, and the reader who cannot see the difference has to open
     * the application to find it.
     */
    private fun eventsIn(matter: DigestContents.Matter): String {
        val events = matter.notifications.size
        val latest = matter.latest.atZone(WARSAW).toLocalDate().format(CLOSING_DATE)

        return if (events == 1) "Ostatnia zmiana: $latest" else "Zmian w tej sprawie: $events, ostatnia: $latest"
    }

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

        /** The reader's clock. A change "on the 12th" is the 12th in Warsaw, not in UTC. */
        val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

        /** The final digit that takes the second case — 2, 3, 4, and so 22, 23, 24. */
        val FEW = 2..4

        /** Except in the teens, where 12, 13 and 14 take the third. */
        val TEENS = 12..14

        /**
         * Six colours, and no more. Every one is stated on the element that uses it,
         * because a client that drops a stylesheet — which is most of them — must still
         * render a message somebody can read. They are deliberately mid-tone rather than
         * pure black on pure white: a client inverting this for dark mode turns pure
         * values into the harshest possible result.
         */
        /** No web font: one is a request a mail client blocks and a face nobody sees. */
        const val FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif"

        const val PAGE = "#f4f5f7"
        const val CARD = "#ffffff"
        const val INK = "#1c1e21"
        const val QUIET = "#5b6068"
        const val RULE = "#e3e5e8"
        const val URGENT = "#a4262c"
    }
}
