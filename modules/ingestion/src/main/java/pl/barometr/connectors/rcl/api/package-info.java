/**
 * What reading RPL's pages produces.
 *
 * The one place a connector publishes anything. Everywhere else the archive is the
 * contract between fetching and deriving: a connector stores bytes and whoever
 * derives from them reads those bytes back. RPL breaks that symmetry because its
 * pages are HTML behind configured selectors, so "how to read this page" is knowledge
 * the fetcher and the deriver genuinely share — and the alternative is the same
 * selectors written out in three modules, breaking in three places the next time the
 * site is redesigned.
 *
 * Published: the page model and the port that produces it. Not published: the
 * selectors, the parsers and the crawl itself, which remain the connector's business.
 *
 * A Java file in a Kotlin module because Kotlin has no package annotations.
 */
@org.springframework.modulith.NamedInterface("rcl-pages")
package pl.barometr.connectors.rcl.api;
