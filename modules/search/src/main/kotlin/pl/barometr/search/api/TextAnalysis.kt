package pl.barometr.search.api

/**
 * Reads text the way the index reads it.
 *
 * Published because the index's analyser is the system's only definition of what two
 * Polish words being "the same word" means, and a caller deciding whether a title
 * carries somebody's keyword has to ask the same question the search box asks.
 * Anything else — a `contains`, a hand-rolled suffix strip — is a second definition
 * that agrees with the first until the day it matters.
 */
interface TextAnalysis {

    /** [text] as the stems the index would store, in order, duplicates kept. */
    fun stemsOf(text: String): List<String>
}
