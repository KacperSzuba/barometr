package pl.barometr.alerts.internal

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import pl.barometr.identity.api.SignedInFromNewDevice

/**
 * Turns "this account was used on a device it has not been used on before" into a
 * message.
 *
 * Identity raises the fact and takes no view on how anybody is told; this context owns
 * delivery, suppression and the consequences of sending mail, so the decision belongs
 * here. All this does is queue it — everything that can stop a message is asked by the
 * handler, at the point where refusing it is free.
 */
@Component
class NewDeviceSignInNotice(private val mails: NewDeviceMailQueue) {

    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun warnAboutNewDevice(signIn: SignedInFromNewDevice) {
        if (mails.queueWarning(signIn)) {
            log.info("Warning {} about a sign-in on a new device", signIn.userId.value)
        }
    }
}
