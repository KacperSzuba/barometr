package pl.barometr.identity.internal.privacy

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.identity.internal.auth.InvalidCredentialsException
import pl.barometr.identity.internal.user.Users
import pl.barometr.shared.ErasureReport

/**
 * Closing an account: the password again, and then everything.
 *
 * The check is here rather than in the endpoint because it is a rule about the operation
 * — an account is closed by somebody who can still prove they own it — and rules that
 * live in controllers are rules that hold only over HTTP.
 */
@Service
class AccountClosure(
    private val users: Users,
    private val erasure: AccountErasure,
    private val passwords: PasswordEncoder,
) {

    @Transactional
    fun closeAccount(user: UserId, password: String): List<ErasureReport> {
        val account = users.byId(user.value) ?: throw InvalidCredentialsException()
        if (!passwords.matches(password, account.passwordHash)) throw InvalidCredentialsException()

        return erasure.eraseAccount(user)
    }
}
