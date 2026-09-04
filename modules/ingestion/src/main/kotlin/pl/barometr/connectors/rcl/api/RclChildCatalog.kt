package pl.barometr.connectors.rcl.api

/** A catalog filed under another, as announced by its parent's register. */
data class RclChildCatalog(val catalogId: String, val name: String)
