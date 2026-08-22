package pl.barometr.connectors.rcl.api

import java.time.LocalDate

/**
 * One file filed under a stage: a draft's text, its justification, an impact
 * assessment, a table of comments from consultation.
 *
 * The end of a chain the change registers could only start. A register names a file
 * and times it to the minute but carries no link to it, so until the catalog page was
 * read this system knew a document existed without knowing where to fetch it.
 *
 * [documentId] is RPL's own identity for the file and the only part of this worth
 * addressing the archive by: a name is edited, a date is a property of the content,
 * and both belong to a version rather than to the document.
 */
data class RclFiledDocument(
    val documentId: String,
    /** The catalog the file is filed *in*, which may be a child of the page it was read from. */
    val catalogId: String,
    val fileName: String,
    /** As printed, so the site root is applied by whoever resolves it. */
    val href: String,
    val author: String?,
    /** The day RPL says the file was created. Day resolution is all the page offers. */
    val createdOn: LocalDate?,
) {

    /**
     * What the file name claims the format is.
     *
     * A claim, not a fact — which is why it is only ever the fallback for the media
     * type the server actually served.
     */
    val extension: String? get() = fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
}
