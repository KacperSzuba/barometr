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

    fun eventOf(item: ResolvedItem): String = when (item.kind) {
        // An act is published once. Being restated by the register is not news.
        LegislativeKind.ACT -> "${LegislativeKind.ACT}:${item.id}"
        // A draft is news each time it stands somewhere new.
        else -> "${LegislativeKind.DRAFT}:${item.id}@${item.stage ?: UNKNOWN_STAGE}"
    }

    fun caseOf(item: ResolvedItem): String = "${item.kind}:${item.id}"

    /**
     * A draft whose stage is not known yet is news exactly once. The alternative —
     * treating every unknown as distinct — would tell somebody about the same draft on
     * every pass until the stage arrives.
     */
    private const val UNKNOWN_STAGE = "unknown"
}
