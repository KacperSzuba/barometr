package pl.barometr.identity.api

import java.security.Principal
import java.util.UUID

/**
 * Who is calling, from what the security chain established.
 *
 * Published because three contexts were working it out separately and a fourth was
 * about to. It is identity's business to say how a verified caller becomes a [UserId],
 * and one copy per context is one place per context for that to drift.
 *
 * Takes `java.security.Principal` rather than a token type: the JDK interface carries
 * everything this needs, because the resource server names the principal after the
 * token's subject and that subject is an identifier this module minted. Reaching for
 * `Jwt` would put a security library on the compile classpath of every context that
 * has an endpoint.
 */
fun callerOf(principal: Principal): UserId =
    runCatching { UserId(UUID.fromString(principal.name)) }
        .getOrElse { throw UnidentifiedCallerException(principal.name) }
