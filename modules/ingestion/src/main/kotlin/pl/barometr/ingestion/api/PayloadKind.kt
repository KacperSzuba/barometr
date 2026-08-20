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
}
