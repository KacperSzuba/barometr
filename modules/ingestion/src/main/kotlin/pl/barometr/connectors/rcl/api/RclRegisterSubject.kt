package pl.barometr.connectors.rcl.api

/**
 * What a register is about, and what it hangs beneath.
 *
 * Worth reading because it is the only statement of the tree's shape that RPL
 * makes in prose: a catalog register says either `w projekcie "…"` or
 * `w katalogu "…"`, and the difference is what reveals that stages contain
 * catalogs of their own rather than documents directly.
 */
data class RclRegisterSubject(
    val scope: RclRegisterScope,
    val name: String,
    val parentScope: RclRegisterScope? = null,
    val parentName: String? = null,
    /** Project id or catalog id, taken from the link beside the heading. */
    val parentId: String? = null,
)
