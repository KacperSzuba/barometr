package pl.barometr.ingestion.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Ingestion's own settings, bound from the `app.ingestion` block.
 *
 * A properties class rather than `@Value` on a constructor parameter, so every
 * setting has one documented home and the same binding rules as the rest of the
 * application.
 */
@ConfigurationProperties(prefix = "app.ingestion")
data class IngestionProperties(
    /**
     * Fraction of a source's declared count that may be missing before the archive
     * is reported as having a gap. Half a percent absorbs the ordinary disagreement
     * between a count published at one moment and an archive read at another,
     * without hiding a page that was genuinely dropped.
     */
    val completenessTolerance: Double = 0.005,
)
