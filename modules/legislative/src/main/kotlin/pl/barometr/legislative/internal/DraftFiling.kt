package pl.barometr.legislative.internal

import pl.barometr.corpus.api.DocumentId
import java.time.LocalDate

/**
 * One file a ministry filed under a draft, as the reader of a card meets it.
 *
 * [documentId] is what makes it reachable: corpus answers for what the file says and
 * what changed in it between versions, and it answers by its own identifier. Null while
 * the catalog page names a file the archive has not fetched, which is the honest state
 * — the reader is told the file exists and that this system does not hold it.
 */
data class DraftFiling(
    val documentId: DocumentId?,
    val fileName: String?,
    /** The folder RPL filed it in — the difference between a bill and a comment on one. */
    val catalogId: String,
    val author: String?,
    val filedOn: LocalDate?,
)
