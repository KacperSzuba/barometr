package pl.barometr.identity.internal.profile

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.Role
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserLookup
import pl.barometr.identity.internal.auth.InvalidCredentialsException

/**
 * Who the caller is, as the rest of the system already describes a user.
 *
 * Reads through [UserLookup] — the module's published read port — rather than
 * reaching for storage: a controller that injects a repository has skipped the one
 * layer whose job is to decide what a user looks like outside this module, and would
 * have to be revisited the day that storage changes.
 */
@RestController
@RequestMapping("/api/v1")
class MeController(private val users: UserLookup) {

    /**
     * Reaching this method body already means the resource server verified
     * signature, issuer, audience and expiry.
     */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): UserResponse {
        // A token whose subject is absent or not a UUID was not minted here; it
        // verified only because it was signed with our key, so it is a credential
        // problem rather than a bad request.
        val userId = runCatching { UserId.parse(requireNotNull(jwt.subject)) }
            .getOrElse { throw InvalidCredentialsException() }

        // A signature can outlive its account: the token stays valid until it
        // expires even if the user was deleted in the meantime.
        val user = users.findById(userId) ?: throw InvalidCredentialsException()

        return UserResponse(user.id.value, user.email, user.roles.map(Role::name))
    }
}
