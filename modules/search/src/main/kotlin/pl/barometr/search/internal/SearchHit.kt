package pl.barometr.search.internal

import pl.barometr.legislative.api.FiledUnderDraft

/** One result, with the parts of it that matched marked up. */
data class SearchHit(
    val id: String,
    val kind: String,
    val title: String,
    /** The title with matches marked, or null when the hit was not on the title. */
    val highlightedTitle: String?,
    /**
     * Passages of the document's text with the matching words marked, for a hit that
     * matched inside a file. Empty for an act or a draft, which have no text of their
     * own, and for a document found by its title alone.
     *
     * This is what makes searching the text worth doing rather than merely possible: a
     * list of files that mention a phrase is a list of things to open, and a list of
     * sentences that mention it is an answer.
     */
    val passages: List<String>,
    val eli: String?,
    val stage: String?,
    val outcome: String?,
    /**
     * The draft this file was filed under, read at query time rather than stored in the
     * index. Null for a hit that is not a document, and for a file no draft names yet.
     */
    val filedUnder: FiledUnderDraft?,
    val score: Double,
)
