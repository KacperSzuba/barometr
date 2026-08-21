/**
 * What search publishes.
 *
 * One port, and deliberately narrow: the index is a derived thing, so what leaves this
 * context is an answer to a question — which acts and drafts a phrase finds — and never
 * a query builder, an index name or a hit. Anything more would let another context
 * depend on how the index is shaped, and the whole reason a second datastore is
 * allowed here is that it can be thrown away and rebuilt.
 *
 * A Java file in a Kotlin module because Kotlin has no package-level annotations.
 */
@org.springframework.modulith.NamedInterface("api")
package pl.barometr.search.api;
