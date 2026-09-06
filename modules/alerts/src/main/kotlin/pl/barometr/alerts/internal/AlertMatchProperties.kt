package pl.barometr.alerts.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How long something that moved is left alone before anybody is told about it, bound
 * from the `app.alerts` block.
 *
 * Not a delay for its own sake. What a judgement reads — which industries an act
 * concerns, where a draft stands, what it is called — is written by listeners on the
 * very same event as the one that buffers the item, running beside each other on the
 * same executor. Judging the moment the item lands means judging against whichever of
 * them happened to finish first, and the answer is then wrong in the one direction
 * nobody can see: a profile watching an industry hears nothing about an act whose
 * industry was recorded a second later, the item is marked judged, and no run looks at
 * it again.
 *
 * A minute, against a cadence of five: the cost is that an alert can be a minute later
 * than it might have been, which no reader can perceive, and the gain is that the
 * derivations an alert rests on have landed before it is decided.
 */
@ConfigurationProperties("app.alerts")
data class AlertMatchProperties(val settleDelay: Duration = Duration.ofMinutes(1)) {
    init {
        require(!settleDelay.isNegative) { "A settling delay does not run backwards: $settleDelay" }
    }
}
