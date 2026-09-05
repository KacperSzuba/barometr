package pl.barometr.identity.internal.twofactor

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.UserId
import java.util.UUID

/**
 * The way back in for somebody who has lost their phone and their recovery codes.
 *
 * There has to be one: a second factor with no way past it turns a lost phone into a
 * lost account, and the support call happens whether or not anybody built for it. What
 * matters is that it is deliberate, that only an operator can do it, and that it leaves
 * a trace.
 *
 * **The trace is not written here.** Every request that changes something passes through
 * the application's audit filter, which records who called what and what came back — so
 * this route is in the log by construction rather than because somebody remembered to
 * add a line. That is also why identity does not depend on the audit context: audit
 * depends on identity, and the trail is assembled from outside both.
 */
@RestController
@RequestMapping("/api/v1/operator/users/{userId}/2fa")
@PreAuthorize("hasRole('OPERATOR')")
class OperatorTwoFactorController(private val enrolment: TwoFactorEnrolment) {

    private val log = LoggerFactory.getLogger(javaClass)

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reset(@PathVariable userId: UUID) {
        // Said out loud as well as audited: a second factor removed by somebody other
        // than its owner is worth a line an operator can find without querying a table.
        log.warn("Second factor for {} reset by an operator", userId)
        enrolment.disable(UserId(userId))
    }
}
