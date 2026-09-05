package pl.barometr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import java.util.UUID

/**
 * The subscribed calendar through the assembled application.
 *
 * One thing here can only be asserted at this level and is the whole feature: the feed
 * URL is reachable with no account at all, because a calendar client has nowhere to put
 * a token and nobody at the keyboard. Everything around it — minting the URL, marking a
 * consultation as answered — still needs to be signed in, and that is the other half of
 * what this checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class CalendarFeedEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `the feed is fetched without signing in, and an unknown token names nothing`() {
        mockMvc.perform(get("$FEED/nie-ma-takiego-tokenu.ics"))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_calendar_feed"}""", false))
    }

    /**
     * The `.ics` suffix is part of the route rather than decoration: several clients
     * decide whether a URL is a calendar by looking at it before fetching anything.
     */
    @Test
    fun `a feed URL without the suffix is not the feed route`() {
        mockMvc.perform(get("$FEED/nie-ma-takiego-tokenu")).andExpect(status().isNotFound)
    }

    @Test
    fun `minting a subscription needs an account`() {
        mockMvc.perform(post("$SUBSCRIPTIONS/${UUID.randomUUID()}")).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a subscription cannot be had for somebody else's profile`() {
        mockMvc.perform(post("$SUBSCRIPTIONS/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_profile"}""", false))

        mockMvc.perform(delete("$SUBSCRIPTIONS/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_profile"}""", false))
    }

    @Test
    fun `marking a consultation as answered needs an account`() {
        mockMvc.perform(put("$CONSULTATIONS/${UUID.randomUUID()}/filing"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a filing is recorded, listed and withdrawn`() {
        val consultation = UUID.randomUUID()

        mockMvc.perform(
            put("$CONSULTATIONS/$consultation/filing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"note":"uwagi wysłane 12 marca"}"""),
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("$CONSULTATIONS/filings"))
            .andExpect(status().isOk)
            .andExpect(content().json("""[{"consultationId":"$consultation","note":"uwagi wysłane 12 marca"}]""", false))

        mockMvc.perform(delete("$CONSULTATIONS/$consultation/filing")).andExpect(status().isNoContent)

        mockMvc.perform(get("$CONSULTATIONS/filings"))
            .andExpect(status().isOk)
            .andExpect(content().json("[]", true))
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a note longer than the column allows is refused at the edge`() {
        mockMvc.perform(
            put("$CONSULTATIONS/${UUID.randomUUID()}/filing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"note":"${"a".repeat(501)}"}"""),
        ).andExpect(status().isBadRequest)
    }

    companion object {
        /** A caller is a user id: the name on the principal is what `callerOf` reads. */
        private const val EWA = "0199bdc7-9a1a-7a1a-8a1a-3a1a5a1a7a1a"

        private const val FEED = "/api/v1/alerts/calendar/feed"
        private const val SUBSCRIPTIONS = "/api/v1/alerts/calendar/subscriptions"
        private const val CONSULTATIONS = "/api/v1/alerts/consultations"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
