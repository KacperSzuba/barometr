package pl.barometr.connectors.rcl

/** Draft, stage and file ids, pulled out of the hrefs RPL uses to link them. */
object RclIdentifiers {

    private val PROJECT = Regex("""/projekt/(\d+)""")
    private val CATALOG = Regex("""/katalog/(\d+)""")

    /**
     * `/docs//1/12409051/13196866/13196867/dokument770751.docx`.
     *
     * Anchored on the two segments that carry meaning rather than on the whole path,
     * because everything to their left is RPL's own storage layout — a doubled slash,
     * a constant `1`, the project and the parent catalog — and none of it is a
     * promise. What the site does state is that a file lives in the catalog named
     * immediately before it and is identified by the number after `dokument`.
     */
    private val FILED_DOCUMENT = Regex("""/(\d+)/dokument(\d+)""")

    fun projectIdIn(href: String): String? = PROJECT.find(href)?.groupValues?.get(1)

    fun catalogIdIn(href: String): String? = CATALOG.find(href)?.groupValues?.get(1)

    /** The catalog a file is filed in, which is the last one named before the file. */
    fun catalogIdOfFileIn(href: String): String? = FILED_DOCUMENT.find(href)?.groupValues?.get(1)

    fun documentIdIn(href: String): String? = FILED_DOCUMENT.find(href)?.groupValues?.get(2)
}
