package pl.barometr.legislative.api

/**
 * The two things this system tracks, named once.
 *
 * Every context that routes, indexes or displays them has to compare against these
 * strings, and the alternative is each spelling `"act"` for itself — one typo away from
 * a filter that silently keeps nothing. They live here because an act and a draft are
 * legislative's concepts; the index and the profiles merely refer to them.
 */
object LegislativeKind {
    const val ACT = "act"
    const val DRAFT = "draft"
}
