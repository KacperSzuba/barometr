package pl.barometr.audit.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.audit.internal.jooq.tables.references.AUDIT_EVENT
import pl.barometr.shared.ErasureReport
import pl.barometr.shared.PersonalDataExtract
import pl.barometr.shared.PersonalDataStore
import pl.barometr.shared.PersonalDataTable
import java.util.UUID

/**
 * The audit trail hands over what it holds about somebody, and deletes none of it.
 *
 * **This is the one refusal in the system, and it is deliberate.** The trail is
 * append-only and tamper-evident: every entry carries the hash of the one before it, and
 * that chain is the whole reason the log is worth anything — "somebody tried to read
 * another account's data and was stopped" means nothing if entries can be removed
 * afterwards. Deleting rows would break the chain for every entry after them, including
 * entries about other people.
 *
 * It is kept on the lawful basis of security and accountability, for as long as the
 * retention policy says, and it is stated in the erasure report rather than left for
 * somebody to discover. What it holds is small by design — who, what, when, and the
 * outcome — and an export hands all of it over.
 */
@Component
class AuditPersonalData(private val dsl: DSLContext) : PersonalDataStore {

    override val category: String = "audit"

    @Transactional(readOnly = true)
    override fun personalDataOf(user: UUID): PersonalDataExtract = PersonalDataExtract(
        category = category,
        tables = listOf(
            PersonalDataTable(
                name = "audit_event",
                rows = dsl.selectFrom(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ACTOR_ID.eq(user))
                    .orderBy(AUDIT_EVENT.SEQUENCE)
                    .fetch()
                    .map { record ->
                        mapOf(
                            "at" to record.at?.toInstant()?.toString(),
                            "action" to record.action,
                            "resource" to record.resource,
                            "outcome" to record.outcome,
                            "status" to record.status?.toString(),
                            "peer" to record.peer,
                        )
                    },
            ),
        ),
    )

    override fun erasePersonalData(user: UUID): ErasureReport = ErasureReport(
        category = category,
        deleted = emptyMap(),
        kept = mapOf(
            "audit_event" to
                "append-only and hash-chained: removing an entry would break every entry after " +
                "it, including entries about other people. Kept for security and accountability, " +
                "and handed over in full on request",
        ),
    )
}
