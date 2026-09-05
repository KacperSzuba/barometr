package pl.barometr.identity.internal.auth

import jakarta.servlet.http.HttpServletRequest

/**
 * As much as a request says about the device it came from.
 *
 * Both fields are the client's word and are treated as such: the user agent is stored
 * unparsed and bounded, and the address is only ever written into an `inet` column,
 * which refuses anything that is not one. Neither decides anything — they are shown to
 * the person whose account it is, who is the only one who can tell whether a session is
 * theirs.
 */
data class ClientFingerprint(val userAgent: String?, val clientIp: String?) {

    companion object {
        /** Nothing at all, for the paths where no request is in hand. */
        val UNKNOWN = ClientFingerprint(null, null)

        /**
         * `remoteAddr` rather than a header read by hand: with
         * `server.forward-headers-strategy: framework` Spring has already resolved the
         * proxy chain, and parsing `X-Forwarded-For` here would be a second, worse
         * implementation of it.
         */
        fun of(request: HttpServletRequest) = ClientFingerprint(
            userAgent = request.getHeader("User-Agent")?.take(USER_AGENT_LENGTH)?.takeIf(String::isNotBlank),
            clientIp = request.remoteAddr?.takeIf(String::isNotBlank),
        )

        /** The bound the column carries, applied before the database has to refuse a login over it. */
        private const val USER_AGENT_LENGTH = 400
    }
}
