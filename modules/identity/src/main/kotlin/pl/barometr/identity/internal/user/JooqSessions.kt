package pl.barometr.identity.internal.user

import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.postgres.extensions.types.Inet
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.SESSION
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** [Sessions] over jOOQ. */
@Repository
@Transactional(readOnly = true)
class JooqSessions(private val dsl: DSLContext) : Sessions {

    /**
     * Opens a session, or restates the one this family already has.
     *
     * The upsert is not defensive: a refresh inside the grace window issues a second
     * token in the same family, and both paths pass through here. One family is one
     * session however many tokens descend from it.
     */
    @Transactional
    override fun open(session: SignedInSession): SignedInSession {
        dsl.insertInto(SESSION)
            .set(SESSION.FAMILY_ID, session.familyId)
            .set(SESSION.USER_ID, session.userId)
            .set(SESSION.USER_AGENT, session.userAgent)
            .set(SESSION.CLIENT_IP, session.clientIp?.let(::inet))
            .set(SESSION.CREATED_AT, at(session.createdAt))
            .set(SESSION.LAST_SEEN_AT, at(session.lastSeenAt))
            .onConflict(SESSION.FAMILY_ID)
            .doUpdate()
            .set(SESSION.LAST_SEEN_AT, at(session.lastSeenAt))
            .execute()

        return session
    }

    override fun byFamily(familyId: UUID): SignedInSession? =
        dsl.selectFrom(SESSION).where(SESSION.FAMILY_ID.eq(familyId)).fetchOne(::toSession)

    /**
     * The address is written only when this request carried one, so a refresh from
     * behind something that strips the header does not erase where the session was last
     * seen from.
     */
    @Transactional
    override fun markSeen(familyId: UUID, at: Instant, clientIp: String?) {
        val update = dsl.update(SESSION).set(SESSION.LAST_SEEN_AT, at(at))
        clientIp?.let(::inet)?.let { update.set(SESSION.CLIENT_IP, it) }

        update.where(SESSION.FAMILY_ID.eq(familyId))
            .and(SESSION.REVOKED_AT.isNull)
            .execute()
    }

    @Transactional
    override fun revoke(familyId: UUID, at: Instant): Boolean =
        dsl.update(SESSION)
            .set(SESSION.REVOKED_AT, at(at))
            .where(SESSION.FAMILY_ID.eq(familyId))
            .and(SESSION.REVOKED_AT.isNull)
            .execute() > 0

    @Transactional
    override fun revokeAllExcept(userId: UUID, keep: UUID, at: Instant): List<UUID> =
        dsl.update(SESSION)
            .set(SESSION.REVOKED_AT, at(at))
            .where(SESSION.USER_ID.eq(userId))
            .and(SESSION.FAMILY_ID.ne(keep))
            .and(SESSION.REVOKED_AT.isNull)
            .returningResult(SESSION.FAMILY_ID)
            .fetch { it.value1()!! }

    override fun liveFor(userId: UUID): List<SignedInSession> =
        dsl.selectFrom(SESSION)
            .where(SESSION.USER_ID.eq(userId))
            .and(SESSION.REVOKED_AT.isNull)
            .orderBy(SESSION.LAST_SEEN_AT.desc())
            .fetch(::toSession)

    override fun countFor(userId: UUID): Int = dsl.fetchCount(SESSION, SESSION.USER_ID.eq(userId))

    override fun countWithUserAgent(userId: UUID, userAgent: String): Int =
        dsl.fetchCount(SESSION, SESSION.USER_ID.eq(userId).and(SESSION.USER_AGENT.eq(userAgent)))

    private fun toSession(record: Record) = SignedInSession(
        familyId = record[SESSION.FAMILY_ID]!!,
        userId = record[SESSION.USER_ID]!!,
        userAgent = record[SESSION.USER_AGENT],
        clientIp = record[SESSION.CLIENT_IP]?.address()?.hostAddress,
        createdAt = record[SESSION.CREATED_AT]!!.toInstant(),
        lastSeenAt = record[SESSION.LAST_SEEN_AT]!!.toInstant(),
        revokedAt = record[SESSION.REVOKED_AT]?.toInstant(),
    )

    /**
     * A literal address, never a hostname: `ofLiteral` parses without asking DNS, so a
     * header somebody controls cannot turn a login into a name lookup. A malformed
     * address is not worth failing a login over — the column simply stays empty.
     */
    private fun inet(value: String): Inet? =
        runCatching { Inet.valueOf(InetAddress.ofLiteral(value)) }.getOrNull()

    private fun at(instant: Instant) = instant.atOffset(ZoneOffset.UTC)
}
