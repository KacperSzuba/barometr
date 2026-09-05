package pl.barometr.identity.internal.auth

import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher

/**
 * What was announced, kept for the test to read.
 *
 * A dozen lines rather than a mocking framework, and shared by the suites that care:
 * registration, sign-out and the device list all publish, and a second copy of this
 * would be a second definition of "what the publisher does".
 */
class RecordingEvents : ApplicationEventPublisher {
    val published = mutableListOf<Any>()

    override fun publishEvent(event: ApplicationEvent) = publishEvent(event as Any)

    override fun publishEvent(event: Any) {
        published += event
    }

    inline fun <reified T> of(): List<T> = published.filterIsInstance<T>()
}
