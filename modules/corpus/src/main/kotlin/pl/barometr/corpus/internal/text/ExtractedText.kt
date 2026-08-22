package pl.barometr.corpus.internal.text

/**
 * What a payload turned out to say, and what it turned out to be.
 *
 * [mediaType] is Tika's reading of the bytes rather than anything the source
 * declared. Both are recorded because they disagree often enough to matter: a
 * ministry files a PDF under a `.docx` name, the connector archives the server's
 * answer, and only the bytes settle it.
 */
data class ExtractedText(val text: String, val mediaType: String?) {

    /**
     * True when the payload parsed but said nothing — most often a scan with no text
     * layer, which is most of what municipal registers publish.
     *
     * Distinct from a parse failure, and treated the same way for now: nothing is
     * recorded, because a version carrying an empty text blob and no chunks reads as
     * "extracted" to everything downstream. It becomes extractable when OCR arrives.
     */
    val isEmpty: Boolean get() = text.isBlank()
}
