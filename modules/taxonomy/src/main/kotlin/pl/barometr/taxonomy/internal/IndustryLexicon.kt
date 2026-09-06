package pl.barometr.taxonomy.internal

import pl.barometr.shared.PkdCode
import tools.jackson.databind.ObjectMapper
import java.io.InputStream

/**
 * What this system knows about how a Polish law announces which industry it concerns.
 *
 * The knowledge, kept as data rather than as code, for the reason the module exists at
 * all: the terms are the thing that will be corrected weekly once the review queue has
 * anything in it, and a correction should be an edit to a file somebody can read, not a
 * change to a matcher nobody wants to touch.
 *
 * [version] is stamped on every verdict it produces, which is what makes a bad edit
 * findable and undoable — and what tells the backlog walk that everything has to be read
 * again. Change the terms, change the version.
 */
class IndustryLexicon(val version: String, val terms: List<IndustryTerm>) {
    init {
        require(version.isNotBlank()) { "A lexicon states its version" }
        require(terms.isNotEmpty()) { "A lexicon with no terms classifies nothing" }
        val duplicates = terms.groupBy { it.code to it.phrase }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "The same term twice would count as two pieces of evidence: $duplicates" }
    }

    companion object {

        /**
         * Reads the lexicon shipped with the application.
         *
         * Deliberately strict: a term this cannot read is a term that would silently
         * stop classifying anything, and a lexicon that half-loaded would show up as a
         * coverage number quietly drifting down. Failing here stops the application
         * instead.
         */
        fun readFrom(source: InputStream, json: ObjectMapper): IndustryLexicon {
            val root = source.use(json::readTree)
            val version = root.path("version").asString()
            // `toList()` first, deliberately: `JsonNode` carries a `map` of its own,
            // which wins over the one every other collection here is read with and
            // quietly returns a single node instead of a list.
            val terms = root.path("terms").toList().map { term ->
                IndustryTerm(
                    code = PkdCode(term.path("code").asString()),
                    stems = TitleTokens.of(term.path("phrase").asString()),
                    weight = term.path("weight").asDouble(),
                )
            }

            return IndustryLexicon(version, terms)
        }
    }
}
