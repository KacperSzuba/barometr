package pl.barometr.connectors.rcl

/** What a register entry did, as far as its wording can be trusted. */
enum class RclChangeKind {
    PROJECT_CREATED,
    CATALOG_CREATED,

    /** A child catalog was filed under this one — how the tree is discovered. */
    CATALOG_ADDED,

    /** A file was filed under this catalog. The event this whole source exists for. */
    DOCUMENT_ADDED,

    ATTRIBUTE_CHANGED,
    OTHER,
}
