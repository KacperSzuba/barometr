package pl.barometr.profiles.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.profiles.internal.jooq.tables.references.INTEREST_PROFILE
import pl.barometr.profiles.internal.jooq.tables.references.PROFILE_INTEREST
import pl.barometr.profiles.internal.jooq.tables.references.PROFILE_VERSION
import pl.barometr.shared.ErasureReport
import pl.barometr.shared.PersonalDataExtract
import pl.barometr.shared.PersonalDataStore
import pl.barometr.shared.PersonalDataTable
import java.util.UUID

/**
 * What profiles holds about somebody, and what happens to it when they leave.
 *
 * All of it goes. A profile is a person's own statement of what they care about — there
 * is no aggregate worth keeping and nobody else's data is entangled with it — so the
 * erasure here is the simple case, and the versions and interests follow the profile by
 * the cascade the schema already declares.
 */
@Component
class ProfilePersonalData(private val dsl: DSLContext) : PersonalDataStore {

    override val category: String = "profiles"

    @Transactional(readOnly = true)
    override fun personalDataOf(user: UUID): PersonalDataExtract {
        val profiles = dsl.selectFrom(INTEREST_PROFILE)
            .where(INTEREST_PROFILE.OWNER_ID.eq(user))
            .orderBy(INTEREST_PROFILE.CREATED_AT)
            .fetch()

        val interests = dsl.select(
            PROFILE_INTEREST.PROFILE_ID,
            PROFILE_INTEREST.VERSION,
            PROFILE_INTEREST.KIND,
            PROFILE_INTEREST.VALUE,
            PROFILE_INTEREST.EXCLUDED,
        )
            .from(PROFILE_INTEREST)
            .join(INTEREST_PROFILE).on(INTEREST_PROFILE.ID.eq(PROFILE_INTEREST.PROFILE_ID))
            .where(INTEREST_PROFILE.OWNER_ID.eq(user))
            .orderBy(PROFILE_INTEREST.PROFILE_ID, PROFILE_INTEREST.VERSION)
            .fetch()

        return PersonalDataExtract(
            category = category,
            tables = listOf(
                PersonalDataTable(
                    name = "interest_profile",
                    rows = profiles.map {
                        mapOf(
                            "id" to it.id.toString(),
                            "name" to it.name,
                            "current_version" to it.currentVersion.toString(),
                            "created_at" to it.createdAt?.toInstant()?.toString(),
                            "updated_at" to it.updatedAt?.toInstant()?.toString(),
                        )
                    },
                ),
                PersonalDataTable(
                    name = "profile_interest",
                    rows = interests.map {
                        mapOf(
                            "profile_id" to it.value1().toString(),
                            "version" to it.value2().toString(),
                            "kind" to it.value3(),
                            "value" to it.value4(),
                            "excluded" to it.value5().toString(),
                        )
                    },
                ),
            ),
        )
    }

    /**
     * Counted before the delete rather than after, because the cascade takes the versions
     * and the interests with the profile and a count afterwards would report zero for
     * rows that certainly existed.
     */
    @Transactional
    override fun erasePersonalData(user: UUID): ErasureReport {
        val profileIds = dsl.select(INTEREST_PROFILE.ID)
            .from(INTEREST_PROFILE)
            .where(INTEREST_PROFILE.OWNER_ID.eq(user))
            .fetch { it.value1() }

        if (profileIds.isEmpty()) return ErasureReport(category, emptyMap(), emptyMap())

        val versions = dsl.fetchCount(PROFILE_VERSION, PROFILE_VERSION.PROFILE_ID.`in`(profileIds))
        val interests = dsl.fetchCount(PROFILE_INTEREST, PROFILE_INTEREST.PROFILE_ID.`in`(profileIds))
        val profiles = dsl.deleteFrom(INTEREST_PROFILE).where(INTEREST_PROFILE.OWNER_ID.eq(user)).execute()

        return ErasureReport(
            category = category,
            deleted = mapOf(
                "interest_profile" to profiles,
                "profile_version" to versions,
                "profile_interest" to interests,
            ),
            kept = emptyMap(),
        )
    }
}
