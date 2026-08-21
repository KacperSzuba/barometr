package pl.barometr.alerts.internal

import pl.barometr.shared.Ids
import java.util.UUID

/** One person's standing instruction about one profile. */
@JvmInline
value class AlertRuleId(val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        fun next(): AlertRuleId = AlertRuleId(Ids.next())
    }
}
