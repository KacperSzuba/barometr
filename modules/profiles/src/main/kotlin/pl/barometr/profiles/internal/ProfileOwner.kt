package pl.barometr.profiles.internal

import pl.barometr.identity.api.CallerIdentity
import pl.barometr.identity.api.UserId
import java.security.Principal

/**
 * Who is calling, which here is the same question as whose profiles these are.
 *
 * Reading the caller out of the token is identity's to define and this asks it. What
 * is decided here is only what an unreadable one costs: a token that verified but
 * names nobody was signed with our key and minted by something else, which is a
 * credential problem rather than a bad request.
 */
fun ownerOf(caller: Principal): UserId =
    CallerIdentity.of(caller) ?: throw UnidentifiedCallerException(caller.name)
