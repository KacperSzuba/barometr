package pl.barometr.taxonomy.internal

import java.text.Normalizer
import java.util.Locale

/**
 * A title cut into the words a lexicon is matched against.
 *
 * Polish inflects everything a law is about — `budowlany`, `budowlanego`,
 * `budowlanych` — so matching is by prefix and the words have to be comparable before
 * that can work: one case, no diacritics, punctuation gone. `ł` is spelled out because
 * Unicode decomposition does not touch it: it is a distinct letter rather than `l` with
 * a mark, so a title normalised by NFD alone keeps it and "prawo budowlane" stops
 * matching a stem written in ASCII.
 *
 * The same transformation exists in legislative, over its own titles, and it is not
 * shared: there it is what a stored `title_normalised` column must equal for a trigram
 * index to work, which makes it that context's storage detail rather than a rule about
 * Polish. Publishing it would tie this classifier to a column it must never know about.
 */
object TitleTokens {

    fun of(title: String): List<String> =
        Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace('ł', 'l')
            .split(NON_ALPHANUMERIC)
            .filter { it.isNotEmpty() }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
}
