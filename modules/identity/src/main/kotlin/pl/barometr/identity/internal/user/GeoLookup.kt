package pl.barometr.identity.internal.user

import java.net.InetAddress

/**
 * One address, looked up in whatever holds the answers.
 *
 * A seam of one method, and it earns its place: everything around the lookup — refusing
 * private networks, choosing a language, swallowing a failure rather than failing a
 * request over a label — is policy this codebase owns and tests, while the lookup itself
 * is a library call over a file. The seam is where those two part company.
 */
fun interface GeoLookup {

    /** Null when the database has nothing for this address. */
    fun recordFor(address: InetAddress): GeoRecord?
}
