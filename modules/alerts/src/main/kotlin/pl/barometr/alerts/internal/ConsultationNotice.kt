package pl.barometr.alerts.internal

import java.time.LocalDate

/**
 * What makes an item a deadline rather than news: a consultation, and the day it
 * closes.
 *
 * Carried beside the draft rather than instead of it. Who hears about this is decided
 * by asking profiles about the *draft* — somebody watches a bill, or a word in its
 * title; nobody has ever subscribed to a consultation by its identifier — while what
 * they are told, and how often, is decided by the consultation. Keeping both is what
 * lets one item be matched as the first and deduplicated as the second.
 */
data class ConsultationNotice(
    val id: String,
    val closesOn: LocalDate,
    /**
     * Which of [ConsultationWarnings.MARKS] this is — the band the consultation was in
     * when it was judged, not the days actually left.
     *
     * It is part of the notification's identity rather than decoration: three warnings
     * about one deadline are three pieces of news, and without the band they would be
     * one, of which two were silently dropped as already told.
     */
    val warnedAt: Int,
) {

    companion object {
        /**
         * The buffer's word for one of these.
         *
         * The same fact as `ck_pending_item_kind`'s third value; the two change
         * together. Not in [pl.barometr.legislative.api.LegislativeKind] with `act` and
         * `draft`, because those are the vocabulary every context routes and indexes
         * by, and nothing outside this one has any use for a consultation as a subject.
         */
        const val KIND = "consultation"
    }
}
