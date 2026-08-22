package pl.barometr.alerts.internal

/**
 * Hands a message to something that will deliver it.
 *
 * A port because the thing behind it is an account somebody has to open. Delivery,
 * suppression and the log are the parts this system owns, and they are testable
 * without one; which provider carries the bytes is a configuration decision, and both
 * of the candidates speak SMTP.
 */
interface EmailTransport {

    /** Throws when the message was not accepted. The queue turns that into a retry. */
    fun send(message: EmailMessage)
}
