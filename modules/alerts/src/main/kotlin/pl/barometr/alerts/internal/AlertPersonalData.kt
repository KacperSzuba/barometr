package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.TableField
import org.jooq.Record
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_DECISION
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_RULE
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_RULE_STAGE
import pl.barometr.alerts.internal.jooq.tables.references.CALENDAR_FEED
import pl.barometr.alerts.internal.jooq.tables.references.CONSULTATION_FILING
import pl.barometr.alerts.internal.jooq.tables.references.DELIVERY_PREFERENCE
import pl.barometr.alerts.internal.jooq.tables.references.DIGEST
import pl.barometr.alerts.internal.jooq.tables.references.EMAIL_DELIVERY
import pl.barometr.alerts.internal.jooq.tables.references.NOTIFICATION
import pl.barometr.alerts.internal.jooq.tables.references.UNSUBSCRIBE_TOKEN
import pl.barometr.shared.ErasureReport
import pl.barometr.shared.PersonalDataExtract
import pl.barometr.shared.PersonalDataStore
import pl.barometr.shared.PersonalDataTable
import java.util.UUID

/**
 * What alerts holds about somebody: everything they were told, and every time they were
 * not told something and why.
 *
 * **One thing survives, and it is not an oversight.** The suppression list is keyed by
 * address rather than by account, and it exists to honour an earlier "stop mailing me" —
 * a bounce, a spam complaint, an unsubscribe. Deleting the account and then writing to
 * that address again because the reason to stop went with it would be the opposite of
 * respecting the request. It is kept, and it is named in the report.
 */
@Component
class AlertPersonalData(private val dsl: DSLContext) : PersonalDataStore {

    override val category: String = "alerts"

    @Transactional(readOnly = true)
    override fun personalDataOf(user: UUID): PersonalDataExtract = PersonalDataExtract(
        category = category,
        tables = listOf(
            table("alert_rule", ALERT_RULE, ALERT_RULE.OWNER_ID, user),
            table("delivery_preference", DELIVERY_PREFERENCE, DELIVERY_PREFERENCE.OWNER_ID, user),
            table("notification", NOTIFICATION, NOTIFICATION.OWNER_ID, user),
            table("alert_decision", ALERT_DECISION, ALERT_DECISION.OWNER_ID, user),
            table("digest", DIGEST, DIGEST.OWNER_ID, user),
            table("email_delivery", EMAIL_DELIVERY, EMAIL_DELIVERY.OWNER_ID, user),
            table("consultation_filing", CONSULTATION_FILING, CONSULTATION_FILING.OWNER_ID, user),
        ),
    )

    @Transactional
    override fun erasePersonalData(user: UUID): ErasureReport = ErasureReport(
        category = category,
        deleted = mapOf(
            // Rules first: the stages hanging off them go by cascade, and counting after
            // the delete would report zero for rows that certainly existed.
            "alert_rule_stage" to countStagesOf(user),
            "alert_rule" to erase(ALERT_RULE, ALERT_RULE.OWNER_ID, user),
            "delivery_preference" to erase(DELIVERY_PREFERENCE, DELIVERY_PREFERENCE.OWNER_ID, user),
            "notification" to erase(NOTIFICATION, NOTIFICATION.OWNER_ID, user),
            "alert_decision" to erase(ALERT_DECISION, ALERT_DECISION.OWNER_ID, user),
            "email_delivery" to erase(EMAIL_DELIVERY, EMAIL_DELIVERY.OWNER_ID, user),
            "digest" to erase(DIGEST, DIGEST.OWNER_ID, user),
            "unsubscribe_token" to erase(UNSUBSCRIBE_TOKEN, UNSUBSCRIBE_TOKEN.OWNER_ID, user),
            "calendar_feed" to erase(CALENDAR_FEED, CALENDAR_FEED.OWNER_ID, user),
            "consultation_filing" to erase(CONSULTATION_FILING, CONSULTATION_FILING.OWNER_ID, user),
        ),
        kept = mapOf(
            "suppressed_address" to
                "held by address to honour an earlier request to stop mailing it; removing it " +
                "would be writing to that address again",
        ),
    )

    private fun countStagesOf(user: UUID): Int =
        dsl.fetchCount(
            ALERT_RULE_STAGE,
            ALERT_RULE_STAGE.RULE_ID.`in`(dsl.select(ALERT_RULE.ID).from(ALERT_RULE).where(ALERT_RULE.OWNER_ID.eq(user))),
        )

    private fun erase(table: Table<*>, owner: TableField<*, UUID?>, user: UUID): Int =
        dsl.deleteFrom(table).where(owner.eq(user)).execute()

    /** Every column of every row this account owns, stringified for a person to read. */
    private fun table(name: String, table: Table<*>, owner: TableField<*, UUID?>, user: UUID) = PersonalDataTable(
        name = name,
        rows = dsl.selectFrom(table).where(owner.eq(user)).fetch().map(::toRow),
    )

    private fun toRow(record: Record): Map<String, String?> =
        record.fields().associate { field -> field.name to record.get(field)?.toString() }
}
