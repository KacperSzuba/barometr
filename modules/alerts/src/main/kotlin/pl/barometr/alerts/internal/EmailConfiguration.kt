package pl.barometr.alerts.internal

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender

/**
 * Wires a transport only when there is somewhere to send.
 *
 * On the property rather than on the bean, because a `@ConditionalOnBean` in a user
 * configuration is evaluated before Boot's own auto-configuration has contributed the
 * mailer — it would be true or false depending on class ordering, which is the worst
 * way for a system to decide whether it sends e-mail.
 *
 * With no `spring.mail.host` there is no transport, and digests simply queue. That is
 * the right behaviour for a developer machine: the alerts still appear in the API, and
 * nothing is quietly lost.
 */
@Configuration
@EnableConfigurationProperties(EmailProperties::class)
class EmailConfiguration {

    @Bean
    @ConditionalOnProperty("spring.mail.host")
    fun smtpEmailTransport(mailer: JavaMailSender, properties: EmailProperties): EmailTransport {
        require(properties.from.isNotBlank()) {
            "app.alerts.email.from must be set when a mail host is configured"
        }
        return SmtpEmailTransport(mailer, properties.from)
    }
}
