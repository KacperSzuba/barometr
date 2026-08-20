package pl.barometr.connectors.rcl

/** Draft and stage ids, pulled out of the hrefs RPL uses to link them. */
object RclIdentifiers {

    private val PROJECT = Regex("""/projekt/(\d+)""")
    private val CATALOG = Regex("""/katalog/(\d+)""")

    fun projectIdIn(href: String): String? = PROJECT.find(href)?.groupValues?.get(1)

    fun catalogIdIn(href: String): String? = CATALOG.find(href)?.groupValues?.get(1)
}
