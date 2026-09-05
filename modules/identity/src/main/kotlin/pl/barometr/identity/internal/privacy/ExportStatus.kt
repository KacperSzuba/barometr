package pl.barometr.identity.internal.privacy

/**
 * Where a request for an export has got to, matching the `CHECK` on `data_export.status`.
 *
 * [FAILED] exists so that a request nothing could assemble says so, rather than staying
 * "requested" for ever — which is indistinguishable from a queue that has stopped, and is
 * the state a statutory deadline is missed inside.
 */
enum class ExportStatus(val wireName: String) {
    REQUESTED("requested"),
    READY("ready"),
    FAILED("failed"),
    ;

    companion object {
        fun of(wireName: String): ExportStatus? = entries.firstOrNull { it.wireName == wireName }
    }
}
