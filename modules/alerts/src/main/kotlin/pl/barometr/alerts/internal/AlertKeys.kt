package pl.barometr.alerts.internal

import pl.barometr.legislative.api.LegislativeKind

/**
 * The two keys the whole promise of "one notification, not eight" rests on, built in
 * one place because they are a contract rather than formatting: change how one is
 * spelled and every notification already sent stops being recognised as sent.
 *
 * They answer two different questions. The **event key** asks "is this the same piece
 * of news" — a draft reaching second reading is news, the same draft restated by
 * tonight's crawl is not. The **case key** asks "is this the same matter" — both of
 * those are the same draft, and somebody who heard about it this morning does not need
 * telling again this afternoon.
 */
object AlertKeys {

    fun eventOf(item: ResolvedItem): String = when {
        // A deadline is one piece of news per warning, however many times the watch sees
        // the same consultation on its way down: the band is what separates the month's
        // notice from the fortnight's, and the day is what makes a ministry's extension
        // news rather than a repeat.
        item.notice != null ->
            "${ConsultationNotice.KIND}:${item.notice.id}@${item.notice.closesOn}#${item.notice.warnedAt}"
        // An act is published once. Being restated by the register is not news.
        item.kind == LegislativeKind.ACT -> "${LegislativeKind.ACT}:${item.id}"
        // A draft is news each time it stands somewhere new.
        else -> "${LegislativeKind.DRAFT}:${item.id}@${item.stage ?: UNKNOWN_STAGE}"
    }

    /**
     * A consultation is a matter of its own, and not the draft's.
     *
     * The case key is what silences a second piece of news within the day, and folding
     * a deadline into the draft's matter would do exactly that: somebody told this
     * morning that the bill moved would not be told this afternoon that they have three
     * days left to write in. Those are different things to know, and only one of them
     * expires.
     */
    fun caseOf(item: ResolvedItem): String =
        item.notice?.let { "${ConsultationNotice.KIND}:${it.id}" } ?: "${item.kind}:${item.id}"

    /**
     * A draft whose stage is not known yet is news exactly once. The alternative —
     * treating every unknown as distinct — would tell somebody about the same draft on
     * every pass until the stage arrives.
     */
    private const val UNKNOWN_STAGE = "unknown"
}
