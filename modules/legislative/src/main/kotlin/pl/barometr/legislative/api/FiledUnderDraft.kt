package pl.barometr.legislative.api

import pl.barometr.corpus.api.DocumentId

/**
 * A draft, seen from one of the files filed under it.
 *
 * What a consumer starting at a document needs and cannot work out: corpus announces a
 * file and says what it is called, and nothing in that announcement says which bill it
 * belongs to. This is the answer, as a value type — the draft's identity and enough of
 * it to name in a result, so a caller showing a passage from a file can say which bill
 * the passage is from without a second question.
 *
 * [fileName] is RPL's name for the file, which is what tells a reader that a hit is in
 * the bill itself rather than in the table of comments on it. Null for a file the
 * archive holds and whose catalog page has not been read.
 */
data class FiledUnderDraft(
    val documentId: DocumentId,
    val draftId: DraftId,
    val draftTitle: String,
    val fileName: String?,
)
