package pl.barometr.taxonomy.internal

import org.springframework.stereotype.Component
import pl.barometr.shared.PkdCode

/**
 * Reads a law's title and says which industries it appears to concern.
 *
 * **The first classifier, and it says so in its name.** It matches a curated lexicon
 * against the title and nothing else — no model, no body text — because that is the
 * version whose every answer can be explained in one line to the person reviewing it,
 * and because the archive it has to get through is a hundred thousand titles rather
 * than a hundred. What it is for is filling `item_industry` at all: until something
 * does, an industry code is a subscription to silence, and every consumer downstream —
 * the profile preview, the alert run, the coverage gauge — is exercising machinery over
 * an empty table.
 *
 * **Evidence combines, it does not add.** Two terms pointing at construction make the
 * verdict surer than either alone, and no amount of them makes it certain: the score is
 * `1 - Π(1 - w)`, which is what "any one of these being right is enough" means
 * arithmetically. Adding weights would let three weak hints outrank the phrase the law
 * is actually named after, and would cross the acceptance threshold on evidence nobody
 * would accept.
 *
 * **A title is not a body.** A law about energy prices and a building code that
 * mentions energy efficiency share a word, so lone stems are written weak on purpose
 * and land in the review queue rather than in somebody's inbox. That is the queue doing
 * its job, not the classifier failing.
 */
@Component
class LexicalIndustryClassifier(private val lexicon: IndustryLexicon) {

    val version: String get() = lexicon.version

    fun industriesIn(title: String): List<IndustryMatch> {
        val words = TitleTokens.of(title)
        if (words.isEmpty()) return emptyList()

        return lexicon.terms
            .filter { it.startsIn(words) != IndustryTerm.NOWHERE }
            .groupBy { it.code }
            .map { (code, matched) -> matchOf(code, matched) }
            .sortedByDescending { it.confidence }
    }

    private fun matchOf(code: PkdCode, matched: List<IndustryTerm>) = IndustryMatch(
        code = code,
        confidence = 1.0 - matched.fold(1.0) { doubt, term -> doubt * (1.0 - term.weight) },
        reasons = matched.map { it.phrase },
    )
}
