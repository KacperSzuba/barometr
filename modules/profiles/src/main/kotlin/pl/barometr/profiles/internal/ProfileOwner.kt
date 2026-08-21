package pl.barometr.profiles.internal

import pl.barometr.identity.api.UserId
import java.security.Principal
import java.util.UUID

/**
 * Who is calling, which here is the same question as whose profiles these are.
 *
 * Takes `java.security.Principal` rather than `@AuthenticationPrincipal Jwt` because
 * the JDK type carries everything this needs: the resource server names the principal
 * after the token's subject, and that subject is a [UserId] we minted ourselves.
 * Reaching for `Jwt` would put a security library on this module's compile classpath
 * to read one string out of it.
 */
fun ownerOf(caller: Principal): UserId =
    runCatching { UserId(UUID.fromString(caller.name)) }
        .getOrElse { throw UnidentifiedCallerException(caller.name) }
