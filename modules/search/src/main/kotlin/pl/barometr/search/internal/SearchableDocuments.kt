package pl.barometr.search.internal

import pl.barometr.corpus.api.DocumentKind

/**
 * Which of the archive's documents are worth searching the text of.
 *
 * Text is extracted from everything the archive holds, including the JSON a register's
 * API returns — a print, a sitting, a process. That text is real and useful for
 * deriving from, and it is not prose: indexing it would fill a search for "energia"
 * with serialised API payloads that happen to contain the word, and bury the bill.
 *
 * So this is an allowlist rather than a filter on what has text, and it is deliberately
 * a statement about *shapes of source data* rather than about media types: a ministry
 * files a bill as a PDF and a table of comments as a spreadsheet, and both are prose
 * somebody wrote for people to read.
 *
 * The list grows with the sources that gain prose. ISAP publishes the text of every act
 * beside the metadata this system archives today, and the day that text is fetched its
 * kind belongs here.
 */
object SearchableDocuments {

    /**
     * What a ministry filed under a draft: the bill's text, its justification, the
     * impact assessment, the letters sending it out for comment, the tables of comments
     * that came back. Everything the corpus is actually made of.
     */
    private val FILED_DOCUMENT = DocumentKind("rcl-filed-document")

    val KINDS: List<DocumentKind> = listOf(FILED_DOCUMENT)

    operator fun contains(kind: DocumentKind): Boolean = kind in KINDS
}
