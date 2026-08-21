package pl.barometr.alerts.internal

import pl.barometr.identity.api.CallerIdentity
import pl.barometr.identity.api.UserId
import java.security.Principal

/**
 * Who is calling, which here is the same question as whose alerts these are.
 *
 * Reading the caller out of the token is identity's to define and this asks it; what
 * an unreadable one costs is this context's decision, and it is the same one profiles
 * makes — a token that verified while naming nobody was minted by something that is
 * not us.
 */
fun readerOf(caller: Principal): UserId =
    CallerIdentity.of(caller) ?: throw UnidentifiedCallerException(caller.name)
