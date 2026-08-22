package pl.barometr.profiles.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.UUID
import pl.barometr.identity.api.callerOf
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.ProfileId

/**
 * A subscriber's own profiles.
 *
 * Every route reads the owner from the token and never from the request, so there is
 * no parameter here that could be pointed at somebody else's data — and a profile that
 * is not yours is reported as absent rather than forbidden, which is the difference
 * between refusing to answer and confirming it exists.
 *
 * Authenticated, not operator: these are the caller's own few rows, and reading or
 * editing them costs nothing anybody else pays for.
 */
@RestController
@RequestMapping("/api/v1/profiles")
class InterestProfileController(
    private val profiles: InterestProfiles,
    private val preview: ProfileMatchPreview,
) {

    @GetMapping
    fun list(caller: Principal): List<ProfileResponse> =
        profiles.ownedBy(callerOf(caller)).map(::describe)

    @GetMapping("/{id}")
    fun profile(caller: Principal, @PathVariable id: UUID): ProfileResponse =
        describe(profiles.read(callerOf(caller), ProfileId(id)))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(caller: Principal, @Valid @RequestBody request: ProfileRequest): ProfileResponse =
        describe(
            profiles.create(callerOf(caller), request.name, request.interests.map(::toInterest)),
        )

    /**
     * The whole set of interests, as one statement. What comes back carries the new
     * version number — the client needs it to ask later what this profile said when an
     * alert fired.
     */
    @PutMapping("/{id}/interests")
    fun revise(
        caller: Principal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: InterestsRequest,
    ): ProfileResponse =
        describe(
            profiles.revise(callerOf(caller), ProfileId(id), request.interests.map(::toInterest)),
        )

    @PatchMapping("/{id}")
    fun rename(
        caller: Principal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: RenameRequest,
    ): ProfileResponse = describe(profiles.rename(callerOf(caller), ProfileId(id), request.name))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(caller: Principal, @PathVariable id: UUID) {
        profiles.delete(callerOf(caller), ProfileId(id))
    }

    @GetMapping("/{id}/versions")
    fun versions(caller: Principal, @PathVariable id: UUID): List<VersionResponse> =
        profiles.history(callerOf(caller), ProfileId(id))
            .map { VersionResponse(it.version, it.createdAt.toString()) }

    @GetMapping("/{id}/versions/{version}")
    fun version(
        caller: Principal,
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ProfileResponse = describe(profiles.readVersion(callerOf(caller), ProfileId(id), version))

    /**
     * What this profile would catch if it fired now — the answer somebody needs while
     * they are still deciding what to put in it.
     *
     * Costs a query per exact address and one search per phrase, which is why it is a
     * route of its own rather than a field on the profile: reading a profile to render
     * a list of them must not run a dozen searches.
     */
    @GetMapping("/{id}/matches")
    fun matches(caller: Principal, @PathVariable id: UUID): PreviewResponse {
        val found = preview.preview(callerOf(caller), ProfileId(id))

        return PreviewResponse(
            version = found.version,
            matches = found.matches.map {
                MatchPayload(
                    kind = it.kind,
                    id = it.id,
                    title = it.title,
                    eli = it.eli,
                    matchedBy = InterestPayload(
                        it.interest.kind.wireName,
                        it.interest.value,
                        it.interest.excluded,
                    ),
                )
            },
            silent = found.silent.map { InterestPayload(it.kind.wireName, it.value, it.excluded) },
            dormant = found.dormant.map {
                DormantPayload(
                    InterestPayload(it.kind.wireName, it.value, it.excluded),
                    NO_SUBJECT_TAGS,
                )
            },
        )
    }

    private fun describe(profile: InterestProfile) = ProfileResponse(
        id = profile.id.value,
        name = profile.name,
        version = profile.version,
        interests = profile.interests.map {
            InterestPayload(it.kind.wireName, it.value, it.excluded)
        },
    )

    /**
     * A kind outside the vocabulary is refused here rather than by a `@Pattern` on the
     * field, which would be the third place stating the same list of kinds.
     */
    private fun toInterest(payload: InterestPayload): Interest {
        val kind = InterestKind.of(payload.kind.trim().lowercase())
            ?: throw InvalidInterestException("kind", payload.kind)
        return Interest(kind, payload.value, payload.excluded)
    }

    data class InterestPayload(
        @field:NotBlank
        val kind: String,
        @field:NotBlank
        @field:Size(max = VALUE_LENGTH)
        val value: String,
        val excluded: Boolean = false,
    )

    data class ProfileRequest(
        @field:NotBlank
        @field:Size(max = NAME_LENGTH)
        val name: String,
        @field:Valid
        @field:Size(max = MAX_INTERESTS)
        val interests: List<InterestPayload> = emptyList(),
    )

    data class InterestsRequest(
        @field:Valid
        @field:Size(max = MAX_INTERESTS)
        val interests: List<InterestPayload> = emptyList(),
    )

    data class RenameRequest(
        @field:NotBlank
        @field:Size(max = NAME_LENGTH)
        val name: String,
    )

    data class ProfileResponse(
        val id: UUID,
        val name: String,
        val version: Int,
        val interests: List<InterestPayload>,
    )

    data class VersionResponse(val version: Int, val createdAt: String)

    data class MatchPayload(
        val kind: String,
        val id: String,
        val title: String,
        val eli: String?,
        val matchedBy: InterestPayload,
    )

    /** An interest that is kept and cannot yet match, with a code saying why. */
    data class DormantPayload(val interest: InterestPayload, val reason: String)

    data class PreviewResponse(
        val version: Int,
        val matches: List<MatchPayload>,
        /** Matchable, and matching nothing today. */
        val silent: List<InterestPayload>,
        val dormant: List<DormantPayload>,
    )

    private companion object {
        // The two length bounds match the `CHECK` constraints they will otherwise hit
        // as a 500 instead of a 400.
        const val NAME_LENGTH = 120
        const val VALUE_LENGTH = 200

        // Not a storage limit — a routing one. Every interest is a clause in the query
        // that decides who hears about a new act, and a profile with thousands of them
        // is a subscription to everything, which is the same as a subscription to
        // nothing once somebody stops reading it.
        const val MAX_INTERESTS = 200

        /**
         * Why an industry or a place matches nothing yet: an act's subject is not
         * recorded until the impact analysis that assigns it exists. A code rather than
         * a sentence, because the sentence belongs to whichever language the reader
         * chose.
         */
        const val NO_SUBJECT_TAGS = "no_subject_tags"
    }
}
