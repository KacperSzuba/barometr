package pl.barometr.profiles.internal

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.InterestedProfile
import pl.barometr.profiles.api.LegislativeItem
import pl.barometr.profiles.api.MatchedInterest
import pl.barometr.profiles.api.ProfileId
import pl.barometr.profiles.api.ProfileMatching
import pl.barometr.profiles.internal.jooq.tables.references.INTEREST_PROFILE
import pl.barometr.profiles.internal.jooq.tables.references.PROFILE_INTEREST
import pl.barometr.search.api.TextAnalysis

/**
 * The push direction: one item, every profile that asked for it.
 *
 * One query over the live versions rather than a loop over profiles, because this runs
 * for every act and every draft of every ingest cycle. The predicates are all things
 * an index can answer — an equality on an address, an equality on an identity, an
 * array containment on stems — which is why the keyword comparison was moved into the
 * database in the first place.
 */
@Component
class ProfileMatchingAdapter(
    private val dsl: DSLContext,
    private val stems: KeywordStemRepository,
    private val analysis: TextAnalysis,
) : ProfileMatching {

    @Transactional
    override fun profilesInterestedIn(item: LegislativeItem): List<InterestedProfile> {
        val titleStems = analysis.stemsOf(item.title)
        stemPendingKeywords()

        val excluded = profilesMatching(item, titleStems, excluded = true)
            .map { it.profile }
            .toSet()

        return profilesMatching(item, titleStems, excluded = false)
            .filterNot { it.profile in excluded }
    }

    /**
     * Fills in the stems of keywords nobody has stemmed yet.
     *
     * Here rather than when the profile is saved, so that saving a profile never
     * depends on the search node being up. The cost is one analyse per new keyword,
     * once, on the first run that sees it.
     */
    private fun stemPendingKeywords() =
        stems.unstemmed().forEach { keyword -> stems.remember(keyword, analysis.stemsOf(keyword)) }

    private fun profilesMatching(
        item: LegislativeItem,
        titleStems: List<String>,
        excluded: Boolean,
    ): List<InterestedProfile> =
        dsl.select(
            INTEREST_PROFILE.ID,
            INTEREST_PROFILE.OWNER_ID,
            INTEREST_PROFILE.CURRENT_VERSION,
            PROFILE_INTEREST.KIND,
            PROFILE_INTEREST.VALUE,
        )
            .from(INTEREST_PROFILE)
            .join(PROFILE_INTEREST)
            .on(PROFILE_INTEREST.PROFILE_ID.eq(INTEREST_PROFILE.ID))
            .and(PROFILE_INTEREST.VERSION.eq(INTEREST_PROFILE.CURRENT_VERSION))
            .where(PROFILE_INTEREST.EXCLUDED.eq(excluded))
            .and(caughtBy(item, titleStems))
            .fetch {
                InterestedProfile(
                    profile = ProfileId(it[INTEREST_PROFILE.ID]!!),
                    owner = UserId(it[INTEREST_PROFILE.OWNER_ID]!!),
                    version = it[INTEREST_PROFILE.CURRENT_VERSION]!!,
                    matchedBy = MatchedInterest(kindOf(it[PROFILE_INTEREST.KIND]!!), it[PROFILE_INTEREST.VALUE]!!),
                )
            }

    /** A stored kind this enum does not know would mean the two drifted apart. */
    private fun kindOf(stored: String): InterestKind =
        InterestKind.of(stored) ?: error("stored kind '$stored'")

    private fun caughtBy(item: LegislativeItem, titleStems: List<String>): Condition =
        DSL.or(
            listOfNotNull(
                item.eli?.let { isInterest(InterestKind.ACT, PROFILE_INTEREST.VALUE.eq(it)) },
                isInterest(InterestKind.DRAFT, PROFILE_INTEREST.VALUE.eq(item.id))
                    .takeIf { item.kind == LegislativeKind.DRAFT },
                keywordCarriedBy(titleStems),
            ),
        )

    private fun isInterest(kind: InterestKind, matched: Condition): Condition =
        PROFILE_INTEREST.KIND.eq(kind.wireName).and(matched)

    /**
     * Every word of the keyword appears in the title, in the index's own reading of
     * both — which is the same thing the phrase search means by a match, so a preview
     * and an alert cannot disagree.
     *
     * A keyword whose stems are empty is left out rather than treated as matching
     * everything: somebody who typed only stopwords asked for nothing in particular,
     * and a subscription to every document ever published is the one outcome nobody
     * intends.
     */
    private fun keywordCarriedBy(titleStems: List<String>): Condition? =
        titleStems.takeIf { it.isNotEmpty() }?.let { stems ->
            isInterest(
                InterestKind.KEYWORD,
                // The cast is not decoration: the driver binds a `varchar[]`, and
                // Postgres has no `text[] <@ varchar[]`.
                DSL.condition(
                    "{0} <@ {1}::text[] and cardinality({0}) > 0",
                    PROFILE_INTEREST.STEMS,
                    DSL.value(stems.distinct().toTypedArray<String?>()),
                ),
            )
        }
}
