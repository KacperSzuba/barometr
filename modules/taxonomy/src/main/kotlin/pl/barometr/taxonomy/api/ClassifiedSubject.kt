package pl.barometr.taxonomy.api

import pl.barometr.legislative.api.LegislativeKind
import java.util.UUID

/**
 * A thing that can be tagged with an industry: one act, or one draft.
 *
 * The kind is legislative's vocabulary rather than a second copy of it, and it is
 * checked here rather than trusted: a subject kind nobody has written a rule for
 * matches nothing, and a typo would look exactly like an act nobody has classified yet.
 */
data class ClassifiedSubject(val kind: String, val id: UUID) {
    init {
        require(kind == LegislativeKind.ACT || kind == LegislativeKind.DRAFT) {
            "A subject is an act or a draft, got '$kind'"
        }
    }

    override fun toString(): String = "$kind:$id"
}
