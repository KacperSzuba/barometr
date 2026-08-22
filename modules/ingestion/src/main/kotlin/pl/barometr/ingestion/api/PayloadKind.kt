package pl.barometr.ingestion.api

enum class PayloadKind(val wireName: String) {
    JSON("json"),
    XML("xml"),
    HTML("html"),
    PDF("pdf"),
    DOC("doc"),
    DOCX("docx"),
    CSV("csv"),
    BINARY("binary"),
    ;

    companion object {
        /**
         * The kind a wire name denotes, or null for one this system does not archive.
         *
         * Doubles as the reading of a file extension, which is not a coincidence: a
         * connector following a link has nothing better than the name to go on when
         * the server declines to say what it served.
         */
        fun of(wireName: String?): PayloadKind? =
            wireName?.lowercase()?.let { name -> entries.firstOrNull { it.wireName == name } }
    }
}
