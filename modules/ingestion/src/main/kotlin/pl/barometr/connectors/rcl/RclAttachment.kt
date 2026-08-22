package pl.barometr.connectors.rcl

import java.net.URI

/**
 * A file as RPL served it.
 *
 * [contentType] is kept because it is the source's own statement about the format,
 * and it is worth more than the extension on the link: the extension is what somebody
 * typed when uploading, the header is what the server will stand behind. Where the two
 * disagree the walk records a schema warning rather than picking a winner quietly.
 *
 * A plain class rather than a `data class`, like [RclPage] and for the same reason:
 * generated equality over a [ByteArray] compares references.
 */
class RclAttachment(
    val url: URI,
    val bytes: ByteArray,
    val contentType: String?,
    val etag: String?,
    val lastModified: String?,
)
