/**
 * The connector SPI: everything a connector needs, and nothing else.
 *
 * A connector implementation depends on this package alone — never on ingestion's
 * internals, on persistence, or on object storage.
 */
@org.springframework.modulith.NamedInterface("api")
package pl.barometr.ingestion.api;
