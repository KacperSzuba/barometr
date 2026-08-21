package pl.barometr.legislative.internal

import java.text.Normalizer
import java.util.Locale

/**
 * The one normalisation of an act's title, used for storing `title_normalised` and
 * for every query against it.
 *
 * It has to be one function because trigram similarity compares stored text against
 * query text: normalise them differently and every match quietly gets worse, in a way
 * no test of either side alone would show.
 *
 * `ł` is spelled out because Unicode decomposition does not touch it — it is a
 * distinct letter, not `l` with a mark — so a title normalised by NFD alone keeps it
 * and "ustawa o świadczeniach" stops matching "ustawa o swiadczeniach".
 */
object ActTitles {

    fun normalise(title: String): String =
        Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace('ł', 'l')
            .replace(NON_ALPHANUMERIC, " ")
            .trim()

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Punctuation and whitespace alike become one separator, so runs collapse. */
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
}
