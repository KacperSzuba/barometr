package pl.barometr.alerts.internal

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.UserLookup

/**
 * The way out, without signing in.
 *
 * Signing in first is the wrong order and always has been: somebody who cannot stop the
 * mail in front of them presses "spam" instead, and one such press costs the sending
 * domain more than the subscription was ever worth. The token in the link is the whole
 * authorisation — it is random, it is theirs, and all it can do is stop mail.
 *
 * Both verbs, deliberately. `POST` is what a mail client calls for one-click
 * unsubscribe under RFC 8058, and `GET` is what a person clicking the link does.
 */
@RestController
@RequestMapping("/api/v1/alerts/unsubscribe")
class UnsubscribeController(
    private val tokens: UnsubscribeTokenRepository,
    private val suppressions: SuppressionRepository,
    private val users: UserLookup,
) {

    @PostMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun oneClick(@PathVariable token: String) {
        stopMailFor(token)
    }

    @GetMapping("/{token}")
    fun clicked(@PathVariable token: String): Confirmation {
        stopMailFor(token)

        return Confirmation("Nie będziemy już wysyłać wiadomości na ten adres.")
    }

    /**
     * A token that names nobody is answered the same way as one that works.
     *
     * Nothing is gained by telling an unauthenticated caller which tokens exist, and
     * an unsubscribe link that reports an error is one somebody escalates to a spam
     * complaint.
     */
    private fun stopMailFor(token: String) {
        val owner = tokens.ownerOf(token) ?: return
        val address = users.findById(owner)?.email ?: return

        suppressions.suppress(address, SuppressionReason.UNSUBSCRIBED)
    }

    data class Confirmation(val message: String)
}
