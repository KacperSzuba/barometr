package pl.barometr.search.internal

import pl.barometr.legislative.api.LegislativeKind

/**
 * One searchable thing, in the shape the index mapping describes.
 *
 * Acts, drafts and documents share an index rather than having one each. A user looking
 * for a law does not know or care whether it has been passed yet — that is precisely
 * what they are searching to find out — and two indexes would mean two queries, two
 * rankings and a decision about how to merge them. A document is in for the same reason
 * one step further down: somebody searching for a phrase does not know whether it is in
 * a bill's title or on page forty of the impact assessment filed under it.
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
    /**
     * What the document says, for entries that are documents. Null for an act or a
     * draft, which are known by their title and have no text of their own — what a bill
     * *says* is in the files filed under it, and those are indexed as themselves.
     *
     * Kept out of `_source` by the mapping and stored as a field instead, so the index
     * holds one copy of a corpus rather than two. Highlighting reads the stored field.
     */
    val content: String? = null,
    val indexedAt: String,
) {
    companion object {
        private const val PREFIX_SEPARATOR = ':'

        const val ACT = LegislativeKind.ACT
        const val DRAFT = LegislativeKind.DRAFT

        /**
         * The index's own kind, unlike the two above.
         *
         * A document is not a legislative thing — it is a file the archive holds, and
         * which bill it belongs to is a question for legislative rather than a property
         * of the file. So the vocabulary that names it belongs here, where the index is
         * described, and not in another context's contract.
         */
        const val DOCUMENT = "document"

        /**
         * Prefixed by kind, so an act and a draft can never collide on a shared index
         * and so a document's id says what it is without opening it.
         */
        fun idOf(kind: String, id: Any): String = "$kind$PREFIX_SEPARATOR$id"

        /** The entity's own identifier, without the prefix this index added. */
        fun idIn(indexId: String): String = indexId.substringAfter(PREFIX_SEPARATOR)
    }
}
