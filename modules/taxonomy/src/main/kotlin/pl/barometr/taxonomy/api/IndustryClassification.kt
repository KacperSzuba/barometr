package pl.barometr.taxonomy.api

import pl.barometr.shared.PkdCode

/**
 * Read port over which industries a law concerns. Nothing outside taxonomy touches its
 * tables.
 *
 * Two directions, because impact routing asks the question both ways. Given a bill,
 * whom does it concern — that is the alert run, and it runs for everything that moves.
 * Given an industry, what would it have caught — that is the preview somebody watches
 * while they are still choosing codes, and an answer that disagreed with the first
 * would be a screen that promises alerts the engine will not send.
 *
 * Only accepted verdicts cross this boundary. What a classifier was unsure about is a
 * queue for somebody to look at, not a fact to route on.
 */
interface IndustryClassification {

    /** The industries this act or draft has been tagged with. */
    fun industriesOf(subject: ClassifiedSubject): List<PkdCode>

    /**
     * What has been tagged with [code] or with anything beneath it, newest first.
     *
     * Coverage is by level: `62` answers with everything in `62.0`, `62.01` and
     * `62.01.Z`, which is what somebody choosing a division means by choosing it.
     */
    fun classifiedUnder(code: PkdCode, limit: Int): List<ClassifiedSubject>
}
