package pl.barometr.search.internal

/**
 * One searchable thing, in the shape the index mapping describes.
 *
 * Acts and drafts share an index rather than having one each. A user looking for a law
 * does not know or care whether it has been passed yet — that is precisely what they
 * are searching to find out — and two indexes would mean two queries, two rankings and
 * a decision about how to merge them.
 */
data class IndexedEntry(
    val id: String,
    val kind: String,
    val title: String,
    val eli: String? = null,
    val actType: String? = null,
    val publisher: String? = null,
    val initiator: String? = null,
    val term: Int? = null,
    val stage: String? = null,
    val outcome: String? = null,
    /** The numbers people quote a draft by: a print number, `UD383`. */
    val identifiers: List<String> = emptyList(),
    val startedOn: String? = null,
    val announcedOn: String? = null,
    val inForceFrom: String? = null,
    val indexedAt: String,
) {
    companion object {
        const val ACT = "act"
        const val DRAFT = "draft"

        /**
         * Prefixed by kind, so an act and a draft can never collide on a shared index
         * and so a document's id says what it is without opening it.
         */
        fun idOf(kind: String, id: Any): String = "$kind:$id"
    }
}
