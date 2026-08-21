package pl.barometr.profiles.internal

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.ProfileId
import pl.barometr.profiles.internal.jooq.tables.references.INTEREST_PROFILE
import pl.barometr.profiles.internal.jooq.tables.references.PROFILE_INTEREST
import pl.barometr.profiles.internal.jooq.tables.references.PROFILE_VERSION
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Profiles and their versions. SQL only.
 */
@Repository
class InterestProfileRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /** The profile with its live version's interests, or null when there is no such profile. */
    fun findCurrent(id: ProfileId): InterestProfile? =
        readProfiles(INTEREST_PROFILE.ID.eq(id.value)).firstOrNull()

    /**
     * A profile as it stood at one version, for an alert that has to explain itself
     * long after the profile moved on.
     */
    fun findVersion(id: ProfileId, version: Int): InterestProfile? {
        val profile = dsl.selectFrom(INTEREST_PROFILE)
            .where(INTEREST_PROFILE.ID.eq(id.value))
            .fetchOne() ?: return null

        val exists = dsl.fetchExists(
            dsl.selectFrom(PROFILE_VERSION)
                .where(PROFILE_VERSION.PROFILE_ID.eq(id.value))
                .and(PROFILE_VERSION.VERSION.eq(version)),
        )
        if (!exists) return null

        return InterestProfile(
            id = id,
            owner = UserId(profile.ownerId!!),
            name = profile.name!!,
            version = version,
            interests = interestsOf(id, version),
        )
    }

    fun listOwnedBy(owner: UserId): List<InterestProfile> =
        readProfiles(INTEREST_PROFILE.OWNER_ID.eq(owner.value))

    /** Every version this profile has had, newest first. */
    fun versionsOf(id: ProfileId): List<ProfileVersion> =
        dsl.selectFrom(PROFILE_VERSION)
            .where(PROFILE_VERSION.PROFILE_ID.eq(id.value))
            .orderBy(PROFILE_VERSION.VERSION.desc())
            .fetch { ProfileVersion(it.version!!, it.createdAt!!.toInstant()) }

    /**
     * Creates a profile at version 1, or returns null when this owner already has one
     * under that name.
     *
     * Null rather than an exception because the uniqueness is the database's answer,
     * not a check this code could make without racing itself — two requests arriving
     * together both find the name free.
     */
    @Transactional
    fun create(owner: UserId, name: String, interests: List<Interest>): InterestProfile? {
        val id = ProfileId(Ids.next())
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

        val created = dsl.insertInto(INTEREST_PROFILE)
            .set(INTEREST_PROFILE.ID, id.value)
            .set(INTEREST_PROFILE.OWNER_ID, owner.value)
            .set(INTEREST_PROFILE.NAME, name)
            .set(INTEREST_PROFILE.CURRENT_VERSION, FIRST_VERSION)
            .set(INTEREST_PROFILE.CREATED_AT, now)
            .set(INTEREST_PROFILE.UPDATED_AT, now)
            .onConflict(INTEREST_PROFILE.OWNER_ID, INTEREST_PROFILE.NAME)
            .doNothing()
            .execute()
        if (created == 0) return null

        writeVersion(id, FIRST_VERSION, interests, now)
        return InterestProfile(id, owner, name, FIRST_VERSION, interests)
    }

    /**
     * Writes [interests] as a new version and points the profile at it.
     *
     * The `UPDATE ... RETURNING` both allocates the number and locks the row, so two
     * edits arriving together are serialised by Postgres rather than by a read the
     * second one would base on a number the first is about to take.
     */
    @Transactional
    fun revise(id: ProfileId, interests: List<Interest>): InterestProfile? {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val profile = dsl.update(INTEREST_PROFILE)
            .set(INTEREST_PROFILE.CURRENT_VERSION, INTEREST_PROFILE.CURRENT_VERSION.plus(1))
            .set(INTEREST_PROFILE.UPDATED_AT, now)
            .where(INTEREST_PROFILE.ID.eq(id.value))
            .returning()
            .fetchOne() ?: return null

        val version = profile.currentVersion!!
        writeVersion(id, version, interests, now)
        return InterestProfile(id, UserId(profile.ownerId!!), profile.name!!, version, interests)
    }

    /**
     * Renames without versioning: what a profile is called changes nothing it matches.
     *
     * The `NOT EXISTS` is what makes a taken name an outcome rather than an exception.
     * Testing for the name first and updating after would be the same query with a gap
     * in the middle; here Postgres evaluates both together. The unique index still
     * stands behind it for the one case this cannot cover — two renames to the same
     * new name, by the same account, in flight at once — where one of them fails hard
     * rather than quietly taking the name twice.
     */
    fun rename(id: ProfileId, name: String): RenameOutcome {
        val renamed = dsl.update(INTEREST_PROFILE)
            .set(INTEREST_PROFILE.NAME, name)
            .set(
                INTEREST_PROFILE.UPDATED_AT,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            )
            .where(INTEREST_PROFILE.ID.eq(id.value))
            .andNotExists(
                dsl.selectOne()
                    .from(TAKEN)
                    .where(TAKEN.OWNER_ID.eq(INTEREST_PROFILE.OWNER_ID))
                    .and(TAKEN.NAME.eq(name))
                    .and(TAKEN.ID.ne(id.value)),
            )
            .execute()
        if (renamed == 1) return RenameOutcome.RENAMED

        val exists = dsl.fetchExists(
            dsl.selectFrom(INTEREST_PROFILE).where(INTEREST_PROFILE.ID.eq(id.value)),
        )
        return if (exists) RenameOutcome.NAME_TAKEN else RenameOutcome.NO_SUCH_PROFILE
    }

    /** Versions and interests go with it, by `ON DELETE CASCADE`. */
    fun delete(id: ProfileId): Boolean =
        dsl.deleteFrom(INTEREST_PROFILE)
            .where(INTEREST_PROFILE.ID.eq(id.value))
            .execute() == 1

    private fun writeVersion(
        id: ProfileId,
        version: Int,
        interests: List<Interest>,
        at: OffsetDateTime,
    ) {
        dsl.insertInto(PROFILE_VERSION)
            .set(PROFILE_VERSION.PROFILE_ID, id.value)
            .set(PROFILE_VERSION.VERSION, version)
            .set(PROFILE_VERSION.CREATED_AT, at)
            .execute()

        if (interests.isEmpty()) return

        dsl.batch(
            interests.map { interest ->
                dsl.insertInto(PROFILE_INTEREST)
                    .set(PROFILE_INTEREST.PROFILE_ID, id.value)
                    .set(PROFILE_INTEREST.VERSION, version)
                    .set(PROFILE_INTEREST.KIND, interest.kind.wireName)
                    .set(PROFILE_INTEREST.VALUE, interest.value)
                    .set(PROFILE_INTEREST.EXCLUDED, interest.excluded)
                    // The same thing chosen twice is one interest, and saying so here
                    // spares the caller a de-duplication it would have to repeat.
                    .onConflictDoNothing()
            },
        ).execute()
    }

    /**
     * One query for the profiles and their live interests, joined on the version the
     * profile currently points at — a second query per profile is how a list endpoint
     * turns into fifty round trips.
     */
    private fun readProfiles(condition: org.jooq.Condition): List<InterestProfile> =
        dsl.select(
            INTEREST_PROFILE.ID,
            INTEREST_PROFILE.OWNER_ID,
            INTEREST_PROFILE.NAME,
            INTEREST_PROFILE.CURRENT_VERSION,
            PROFILE_INTEREST.KIND,
            PROFILE_INTEREST.VALUE,
            PROFILE_INTEREST.EXCLUDED,
        )
            .from(INTEREST_PROFILE)
            .leftJoin(PROFILE_INTEREST)
            .on(PROFILE_INTEREST.PROFILE_ID.eq(INTEREST_PROFILE.ID))
            .and(PROFILE_INTEREST.VERSION.eq(INTEREST_PROFILE.CURRENT_VERSION))
            .where(condition)
            .orderBy(INTEREST_PROFILE.NAME, PROFILE_INTEREST.KIND, PROFILE_INTEREST.VALUE)
            .fetchGroups(INTEREST_PROFILE.ID)
            .map { (id, rows) ->
                val head = rows.first()
                InterestProfile(
                    id = ProfileId(id!!),
                    owner = UserId(head[INTEREST_PROFILE.OWNER_ID]!!),
                    name = head[INTEREST_PROFILE.NAME]!!,
                    version = head[INTEREST_PROFILE.CURRENT_VERSION]!!,
                    interests = rows.mapNotNull(::toInterest),
                )
            }
            // `fetchGroups` keeps insertion order, but only within one profile's rows;
            // the list itself is ordered here so a client sees profiles by name.
            .sortedBy { it.name }

    private fun interestsOf(id: ProfileId, version: Int): List<Interest> =
        dsl.selectFrom(PROFILE_INTEREST)
            .where(PROFILE_INTEREST.PROFILE_ID.eq(id.value))
            .and(PROFILE_INTEREST.VERSION.eq(version))
            .orderBy(PROFILE_INTEREST.KIND, PROFILE_INTEREST.VALUE)
            .fetch {
                // A kind the CHECK constraint accepted and this enum does not know
                // would mean the two drifted apart, which is not a caller's mistake.
                val kind = InterestKind.of(it.kind!!) ?: error("stored kind '${it.kind}'")
                Interest(kind, it.value!!, it.excluded!!)
            }

    /** Null for the empty side of the outer join — a profile whose version chose nothing. */
    private fun toInterest(row: Record): Interest? {
        val kind = row[PROFILE_INTEREST.KIND]?.let(InterestKind::of) ?: return null
        return Interest(kind, row[PROFILE_INTEREST.VALUE]!!, row[PROFILE_INTEREST.EXCLUDED]!!)
    }

    private companion object {
        const val FIRST_VERSION = 1

        /**
         * The same table under a second name, so the subquery can say "another profile
         * of this owner" — jOOQ would otherwise correlate both sides to the row being
         * updated, and the condition would compare the name to itself.
         */
        val TAKEN = INTEREST_PROFILE.`as`("taken")
    }
}
