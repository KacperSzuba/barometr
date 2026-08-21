package pl.barometr.profiles.internal

import org.springframework.stereotype.Service
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.search.api.TitleMatch
import pl.barometr.search.api.TitleSearch
import pl.barometr.shared.Eli
import java.util.UUID

/**
 * Answers what a profile would catch if it fired now.
 *
 * Exact addresses are resolved against the catalog and phrases against the index, on
 * purpose: an ELI either names an act or does not, and asking the index would make the
 * answer depend on when it was last rebuilt. A phrase has no such answer — matching
 * Polish text is what the index is for — so a keyword goes there and nowhere else.
 */
@Service
class ProfileMatchPreview(
    private val profiles: InterestProfiles,
    private val catalog: LegislativeCatalog,
    private val titles: TitleSearch,
) {

    fun preview(owner: UserId, id: ProfileId): ProfilePreview {
        val profile = profiles.read(owner, id)
        // Dormant first, and regardless of whether it includes or excludes: a kind
        // nothing can match yet cannot exclude anything either.
        val (dormant, active) = profile.interests.partition { it.kind in DORMANT_KINDS }
        val (excluded, included) = active.partition { it.excluded }

        val found = included.flatMap(::matchesFor)
        val refusedIds = refusedIdentities(excluded)
        // An address is refused as an address. Resolving it to an identity first would
        // make the exclusion depend on the act still being in the catalog, and a match
        // that came from the index carries its address with it.
        val refusedElis = excluded.filter { it.kind == InterestKind.ACT }.map { it.value }.toSet()
        val kept = found.filterNot { it.identity in refusedIds || it.eli in refusedElis }

        return ProfilePreview(
            version = profile.version,
            matches = kept.distinctBy { it.identity },
            silent = included.filterNot { interest -> kept.any { it.interest == interest } },
            dormant = dormant,
        )
    }

    /**
     * What the exclusions name, resolved the same way the inclusions were.
     *
     * A phrase goes back to the index rather than being compared to the titles as
     * text, and that is the whole point: the index stems Polish, so somebody excluding
     * *drony* is excluding *dronach* too. A `contains` here would have let the same
     * word include an act and fail to exclude it, which is the sort of difference
     * nobody reports as a bug — they just stop trusting the alerts.
     */
    private fun refusedIdentities(excluded: List<Interest>): Set<Pair<String, String>> =
        excluded.flatMap { interest ->
            when (interest.kind) {
                InterestKind.KEYWORD ->
                    titles.titlesMatching(interest.value, PER_KEYWORD).map { it.kind to it.id }

                InterestKind.DRAFT -> listOf(TitleMatch.DRAFT to interest.value)

                // An act is refused by address above; the other two are partitioned out
                // as dormant before anything gets here.
                InterestKind.ACT, InterestKind.PKD, InterestKind.REGION -> emptyList()
            }
        }.toSet()

    private val ProfileMatch.identity: Pair<String, String> get() = kind to id

    private fun matchesFor(interest: Interest): List<ProfileMatch> = when (interest.kind) {
        InterestKind.ACT -> catalog.actByEli(Eli(interest.value))
            ?.let { listOf(ProfileMatch(interest, TitleMatch.ACT, it.id.value.toString(), it.title, it.eli.value)) }
            .orEmpty()

        InterestKind.DRAFT -> catalog.draftById(DraftId(UUID.fromString(interest.value)))
            ?.let { listOf(ProfileMatch(interest, TitleMatch.DRAFT, it.id.value.toString(), it.title, null)) }
            .orEmpty()

        InterestKind.KEYWORD -> titles.titlesMatching(interest.value, PER_KEYWORD)
            .map { ProfileMatch(interest, it.kind, it.id, it.title, it.eli) }

        // Partitioned out above; reaching here would mean the two lists disagree.
        InterestKind.PKD, InterestKind.REGION -> error("${interest.kind} is dormant")
    }

    private companion object {
        /**
         * A phrase can find thousands, and a preview is read by a person deciding
         * whether they typed the right word — the first few answer that, and the rest
         * would only make the page slow enough to stop being consulted.
         *
         * It bounds exclusions too, and there it is a real limit worth knowing: an
         * exclusion can only remove what both searches reached. This is a preview of a
         * profile, not the routing of a document — when an act actually arrives, the
         * question asked of it is whether *that act* matches, which no limit truncates.
         */
        const val PER_KEYWORD = 10

        /**
         * The kinds nothing can match yet: what an act is *about* is not recorded
         * anywhere until impact analysis assigns it, and neither an industry code nor a
         * place appears in a title often enough to fake it with a text search. Stated
         * here once, so the preview and the response mean the same thing by it.
         */
        val DORMANT_KINDS = setOf(InterestKind.PKD, InterestKind.REGION)
    }
}
