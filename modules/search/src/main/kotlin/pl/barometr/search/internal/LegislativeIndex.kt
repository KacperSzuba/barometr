package pl.barometr.search.internal

import java.time.Instant

/**
 * The index everything searchable is written to, and the analyser that makes it work
 * in Polish.
 *
 * **Why the analyser is not simply `polish`.** The plugin's stemmer is stochastic: it
 * was trained on a corpus and guesses, which is fine on ordinary prose and wrong on
 * exactly the three words every Polish legislative title is built from. Measured
 * against a running node:
 *
 * ```
 * ustawa → ustawa   ustawy → ustawyć   ustaw → ustawe   ustawami → ustaw
 * projekt → projekt projekcie → projeky projekty → projekty
 * zmiana → zmiać    zmiany → zmiany    zmianie → zmiano
 * ```
 *
 * A query for "ustawy o cenach energii" would therefore not find "Ustawa o cenach
 * energii" — which is the specification's own acceptance test, failed by the default
 * configuration. A `stemmer_override` ahead of the stemmer folds those three families
 * by hand; everything else the stemmer already gets right, and was checked rather than
 * assumed: uchwała, poprawka, przepis, komisja, energia, cena, prawo and kodeks all
 * fold correctly on their own, so the override list stays three words long.
 *
 * **Why the stopword list is written out.** Elasticsearch's `stop` filter accepts
 * `_polish_` and then removes nothing: the Polish list lives inside the plugin's own
 * analyser and is not one of the predefined sets a custom chain can name. Measured on
 * a node — "ustawa o zmianie nie tak jak w z na" keeps every preposition. So the list
 * is Lucene's own, lifted out of `org/apache/lucene/analysis/pl/stopwords.txt` in the
 * plugin and inlined, which keeps the definition complete: it works against any node
 * carrying the plugin, without a file having to be put somewhere first.
 *
 * **Why an alias.** Reads go through [ALIAS] and writes to a concrete index behind it,
 * so a mapping change is a fresh index built alongside the live one and an atomic
 * switch — never a window with no index at all. The index is derived and rebuildable
 * from Postgres, which is what makes that cheap.
 */
object LegislativeIndex {

    /** What readers query, and what incremental writes go through. Never an index name. */
    const val ALIAS = "legislative"

    /**
     * Concrete indices are named for the moment they were built, so a rebuild is a new
     * index beside the live one and the switch is a single atomic alias update. The
     * prefix is what lets the old ones be found and dropped afterwards.
     */
    const val PREFIX = "legislative-"

    /**
     * The analyser the title field is read with, named here because two things ask for
     * it by name — the mapping in the definition below, and anything stemming text the
     * way this index stems it.
     */
    const val ANALYZER = "polish_legal"

    const val DEFINITION = "/search/legislative-index.json"

    fun nameFor(builtAt: Instant): String = "$PREFIX${builtAt.toEpochMilli()}"
}
