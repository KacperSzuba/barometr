package pl.barometr.alerts.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.callerOf
import pl.barometr.profiles.api.ProfileId
import java.security.Principal
import java.util.UUID

/**
 * Standing instructions: which of my profiles are worth waking me for.
 *
 * Authenticated, and every route reads the owner from the token. A rule names a
 * profile by identifier, so the one thing this must never do is take the caller's word
 * that the profile is theirs — [AlertRules] asks the profiles context instead.
 */
@RestController
@RequestMapping("/api/v1/alerts/rules")
class AlertRuleController(private val rules: AlertRules) {

    @GetMapping
    fun list(caller: Principal): List<RuleResponse> =
        rules.ownedBy(callerOf(caller)).map(::describe)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(caller: Principal, @Valid @RequestBody request: RuleRequest): RuleResponse =
        describe(
            rules.create(
                callerOf(caller),
                ProfileId(request.profileId),
                request.stages,
                request.urgencyChosen(),
                request.minimumSignificance,

            ),
        )

    /**
     * States the whole rule, like the interests do: the stages sent are the stages
     * watched, so narrowing is expressed by sending fewer.
     */
    @PutMapping("/{id}")
    fun update(
        caller: Principal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: RuleUpdate,
    ): RuleResponse =
        describe(
            rules.update(
                callerOf(caller),
                AlertRuleId(id),
                request.enabled,
                request.stages,
                request.urgencyChosen(),
                request.minimumSignificance,

            ),
        )

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(caller: Principal, @PathVariable id: UUID) {
        rules.delete(callerOf(caller), AlertRuleId(id))
    }

    private fun describe(rule: AlertRule) = RuleResponse(
        id = rule.id.value,
        profileId = rule.profile.value,
        enabled = rule.enabled,
        stages = rule.stages,
        urgency = rule.urgency.wireName,
        minimumSignificance = rule.minimumSignificance,

    )

    data class RuleRequest(
        val profileId: UUID,
        /** Empty means every stage — somebody who has not narrowed has not asked to hear less. */
        @field:Size(max = MAX_STAGES)
        val stages: Set<String> = emptySet(),
        /**
         * `critical` is what carries a match out of the digest window and through the
         * quiet hours. Absent means ordinary, which is what almost everything is.
         */
        val urgency: String? = null,
        /**
         * How much a match has to matter before this rule speaks. Zero is the honest
         * default: somebody who has not asked to hear less has not asked to hear less.
         */
        @field:Min(0) @field:Max(HIGHEST_SIGNIFICANCE)
        val minimumSignificance: Int = 0,

    ) {
        fun urgencyChosen(): Urgency = urgencyIn(urgency)
    }

    data class RuleUpdate(
        val enabled: Boolean = true,
        @field:Size(max = MAX_STAGES)
        val stages: Set<String> = emptySet(),
        val urgency: String? = null,
        /**
         * How much a match has to matter before this rule speaks. Zero is the honest
         * default: somebody who has not asked to hear less has not asked to hear less.
         */
        @field:Min(0) @field:Max(HIGHEST_SIGNIFICANCE)
        val minimumSignificance: Int = 0,

    ) {
        fun urgencyChosen(): Urgency = urgencyIn(urgency)
    }

    data class RuleResponse(
        val id: UUID,
        val profileId: UUID,
        val enabled: Boolean,
        val stages: Set<String>,
        val urgency: String,
        val minimumSignificance: Int,

    )

    private companion object {
        /** An urgency nobody implemented would look like a setting and behave like none. */
        fun urgencyIn(chosen: String?): Urgency =
            chosen?.trim()?.lowercase()?.let { Urgency.of(it) ?: throw InvalidCadenceException(it) }
                ?: Urgency.NORMAL

        /**
         * More stages than the path has. Naming them all is the same as naming none,
         * and a set this size is a client sending something it did not mean.
         */
        const val MAX_STAGES = 40

        /** The top of the scale, as an annotation needs it. */
        const val HIGHEST_SIGNIFICANCE = Significance.MAXIMUM.toLong()
    }
}
