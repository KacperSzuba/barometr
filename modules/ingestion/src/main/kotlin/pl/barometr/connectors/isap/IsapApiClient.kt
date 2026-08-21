package pl.barometr.connectors.isap

import org.springframework.web.util.UriComponentsBuilder
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.http.SourceHttpClient
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.ingestion.api.SourceAccessDeniedException
import pl.barometr.ingestion.api.SourceFetchException
import pl.barometr.shared.Eli
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * Typed access to the ELI API that ISAP publishes.
 *
 * Everything specific to how this source speaks lives here: that a search states
 * `totalCount` for the whole filter rather than for the page, that `pubDateFrom`
 * filters on the day an act appeared in the journal while `dateFrom` filters on the
 * day it was signed, and — the fact the whole connector is shaped around — that a
 * listing item carries the act's *complete* metadata. There is no detail endpoint
 * worth calling: references, dates and Sejm print numbers all arrive with the list,
 * so a year of Dziennik Ustaw costs a handful of requests instead of two thousand.
 */
class IsapApiClient(
    private val httpClient: SourceHttpClient,
    private val baseUrl: URI,
    private val json: ObjectMapper,
) {

    /** The journals, and which years each of them actually has. */
    fun publishers(): List<IsapPublisher> {
        val tree = json.readTree(read(uriOf(PUBLISHERS_PATH)))
        if (!tree.isArray) throw SourceFetchException(PUBLISHERS_PATH, "expected an array of publishers")

        return tree.mapNotNull { node ->
            val code = node.path("code").asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            IsapPublisher(
                code = code,
                name = node.path("name").asString()?.takeIf { it.isNotBlank() } ?: code,
                years = node.path("years").mapNotNull { year -> year.takeIf { it.isInt }?.asInt() },
            )
        }
    }

    /** One page of a journal's year, the unit a backfill partition is read in. */
    fun actsInYear(publisher: String, year: Int, offset: Int, limit: Int): IsapPage =
        readPage(searchUri(publisher, offset, limit, year = year, publishedFrom = null))

    /**
     * One page of what a journal published on or after [publishedFrom].
     *
     * The filter is publication in the journal, not the date on the act: those
     * differ by weeks, and acts carry announcement dates that are plainly wrong
     * (the archive holds one dated 2206). Publication is the date this source
     * orders itself by, so it is the one an incremental pass can trust.
     */
    fun actsPublishedSince(publisher: String, publishedFrom: LocalDate, offset: Int, limit: Int): IsapPage =
        readPage(searchUri(publisher, offset, limit, year = null, publishedFrom = publishedFrom))

    private fun readPage(uri: URI): IsapPage {
        val tree = json.readTree(read(uri))
        val items = tree.path("items")
        if (!items.isArray) throw SourceFetchException(resourceOf(uri), "response carried no items array")

        val warnings = mutableListOf<SchemaWarning>()
        val acts = items.mapIndexedNotNull { index, item ->
            val address = item.path(ELI_FIELD).asString()
            val eli = address?.let(Eli::parseOrNull)
            if (eli == null) {
                warnings += SchemaWarning(
                    path = "items[$index].$ELI_FIELD",
                    kind = if (address.isNullOrBlank()) {
                        SchemaWarning.Kind.MISSING_FIELD
                    } else {
                        SchemaWarning.Kind.UNEXPECTED_TYPE
                    },
                    detail = address,
                )
                return@mapIndexedNotNull null
            }

            val changedAt = item.path(CHANGE_DATE_FIELD).asString()?.let { stamp ->
                timestampOf(stamp) ?: run {
                    warnings += SchemaWarning(
                        path = "items[$index].$CHANGE_DATE_FIELD",
                        kind = SchemaWarning.Kind.UNEXPECTED_TYPE,
                        detail = stamp,
                    )
                    null
                }
            }

            IsapAct(eli = eli, changedAt = changedAt, body = item)
        }

        return IsapPage(
            acts = acts,
            // Falling back to the page size would make a missing total look like a
            // finished partition, so the absence is reported instead.
            totalCount = tree.path(TOTAL_COUNT_FIELD).takeIf { it.isInt }?.asInt()
                ?: throw SourceFetchException(resourceOf(uri), "response stated no $TOTAL_COUNT_FIELD"),
            itemsServed = items.size(),
            warnings = warnings,
        )
    }

    private fun searchUri(
        publisher: String,
        offset: Int,
        limit: Int,
        year: Int?,
        publishedFrom: LocalDate?,
    ): URI {
        val uri = UriComponentsBuilder.fromUri(baseUrl)
            .path(SEARCH_PATH)
            .queryParam("publisher", publisher)
            .queryParam("limit", limit)
            .queryParam("offset", offset)
        year?.let { uri.queryParam("year", it) }
        publishedFrom?.let { uri.queryParam("pubDateFrom", it) }

        return uri.build().toUri()
    }

    private fun uriOf(path: String): URI =
        UriComponentsBuilder.fromUri(baseUrl).path(path).build().toUri()

    private fun read(uri: URI): ByteArray =
        when (val outcome = httpClient.fetch(HttpFetch(uri))) {
            is HttpOutcome.Fetched -> outcome.body
            is HttpOutcome.Refused -> throw SourceAccessDeniedException(resourceOf(uri), outcome.detail)
            // A failure, not a bug: the queue retries it with backoff.
            is HttpOutcome.Failed -> throw SourceFetchException(resourceOf(uri), outcome.detail)
            // Only sent for a conditional request, and this API supports none.
            HttpOutcome.NotModified -> throw SourceFetchException(resourceOf(uri), "unexpected 304")
        }

    /**
     * What was asked for, without the host. The query is part of it: `/acts/search`
     * alone would not say which journal or which year was refused, and that is
     * exactly what the run report has to record.
     */
    private fun resourceOf(uri: URI): String =
        uri.path + (uri.query?.let { "?$it" } ?: "")

    /**
     * Null rather than an exception. A stamp we cannot read costs the cheap
     * "has anything moved" check, which then falls back to reading the window —
     * correct, only more expensive.
     */
    private fun timestampOf(value: String): LocalDateTime? =
        try {
            LocalDateTime.parse(value)
        } catch (malformed: DateTimeParseException) {
            null
        }

    private companion object {
        const val PUBLISHERS_PATH = "/acts"
        const val SEARCH_PATH = "/acts/search"
        const val ELI_FIELD = "ELI"
        const val CHANGE_DATE_FIELD = "changeDate"
        const val TOTAL_COUNT_FIELD = "totalCount"
    }
}
