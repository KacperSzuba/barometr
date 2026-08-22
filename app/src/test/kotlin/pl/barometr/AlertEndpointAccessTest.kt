package pl.barometr

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import java.util.UUID

/**
 * Standing instructions through the assembled application.
 *
 * A rule names a profile by identifier, and an identifier is a thing people paste. The
 * test this class exists for is the one where somebody points a rule at a profile that
 * is not theirs — the module's own tests hand it an owner, and would still pass if the
 * controller had taken one from the request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class AlertEndpointAccessTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun setUp() {
        // Raw SQL rather than the generated tables: reaching into another context's
        // internals is what `ModularityTest` forbids, and a test is not an exemption.
        dsl.execute("DELETE FROM alerts.alert_rule")
        dsl.execute("DELETE FROM alerts.delivery_preference")
        dsl.execute("DELETE FROM profiles.interest_profile")
    }

    @Test
    fun `an anonymous caller is refused`() {
        mockMvc.perform(get(RULES)).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `an account puts a standing instruction on its own profile`() {
        val profile = createProfile()

        mockMvc.perform(
            post(RULES).contentType(MediaType.APPLICATION_JSON)
                .content("""{"profileId":"$profile","stages":["senate"]}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.stages[0]").value("senate"))

        mockMvc.perform(get(RULES)).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a second rule for the same profile is a conflict`() {
        val profile = createProfile()
        createRule(profile)

        mockMvc.perform(
            post(RULES).contentType(MediaType.APPLICATION_JSON)
                .content("""{"profileId":"$profile"}"""),
        ).andExpect(status().isConflict)
    }

    /**
     * The check that cannot be made anywhere but here: the profile belongs to somebody
     * else, and the caller holds nothing but its identifier.
     */
    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a rule cannot be pointed at another account's profile`() {
        val profile = createProfile()

        mockMvc.perform(
            post(RULES).with(marek).contentType(MediaType.APPLICATION_JSON)
                .content("""{"profileId":"$profile"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_profile"}""", false))
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a profile that does not exist is answered the same way`() {
        mockMvc.perform(
            post(RULES).contentType(MediaType.APPLICATION_JSON)
                .content("""{"profileId":"${UUID.randomUUID()}"}"""),
        ).andExpect(status().isNotFound)
    }

    /**
     * Somebody who has never chosen a cadence still has one, and the endpoint says what
     * it is. Making the client invent the default is how the client's idea of it drifts
     * from the server's.
     */
    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a cadence nobody set reads as the default rather than as absent`() {
        mockMvc.perform(get(PREFERENCES))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mode").value("immediate"))
            .andExpect(jsonPath("$.zone").value("Europe/Warsaw"))
            .andExpect(jsonPath("$.quietFrom").doesNotExist())
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a cadence is stated whole and read back`() {
        mockMvc.perform(
            put(PREFERENCES).contentType(MediaType.APPLICATION_JSON).content(
                """{"mode":"weekly","atHour":8,"onWeekday":1,"zone":"Europe/Warsaw","quietFrom":22,"quietTo":7}""",
            ),
        ).andExpect(status().isOk)

        mockMvc.perform(get(PREFERENCES))
            .andExpect(jsonPath("$.mode").value("weekly"))
            .andExpect(jsonPath("$.onWeekday").value(1))
            .andExpect(jsonPath("$.quietTo").value(7))
    }

    /** A weekly digest with no day is a window that never closes. */
    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `half a cadence is refused rather than stored`() {
        listOf(
            """{"mode":"weekly","atHour":8}""",
            """{"mode":"daily"}""",
            """{"mode":"co godzine"}""",
            """{"mode":"daily","atHour":8,"zone":"Mars/Olympus"}""",
            """{"mode":"immediate","quietFrom":22}""",
        ).forEach { body ->
            mockMvc.perform(put(PREFERENCES).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
        }
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a rule can be marked worth the quiet hours`() {
        val profile = createProfile()

        mockMvc.perform(
            post(RULES).contentType(MediaType.APPLICATION_JSON)
                .content("""{"profileId":"$profile","urgency":"critical"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.urgency").value("critical"))
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `an account sees its own alerts and its own decisions, and nobody else's`() {
        mockMvc.perform(get(ALERTS)).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
        mockMvc.perform(get("$ALERTS/decisions")).andExpect(status().isOk)
        mockMvc.perform(get("$ALERTS/digests")).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    /**
     * Stopping the mail must not require signing in: somebody who cannot unsubscribe
     * from the message in front of them presses "spam" instead, and one such press
     * costs the domain more than the subscription was worth.
     */
    @Test
    fun `unsubscribing needs no account, and an unknown token says the same thing`() {
        mockMvc.perform(get("$ALERTS/unsubscribe/nie-ma-takiego-tokenu"))
            .andExpect(status().isOk)

        mockMvc.perform(post("$ALERTS/unsubscribe/nie-ma-takiego-tokenu").with(csrf()))
            .andExpect(status().isNoContent)
    }

    /**
     * The bounce webhook is open to a machine with no account, so the secret is the
     * whole authorisation — and it is unset here, which must mean "refuse everything"
     * rather than "let everybody through".
     */
    @Test
    fun `a bounce report without the shared secret changes nothing`() {
        mockMvc.perform(
            post("$ALERTS/email-events").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ewa@example.com","event":"bounced"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().json("""{"error":"invalid_webhook_secret"}""", false))
    }

    private fun createProfile(): String =
        mockMvc.perform(
            post(PROFILES).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Budowlanka","interests":[]}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
            .let { ID_IN_RESPONSE.find(it)!!.groupValues[1] }

    private fun createRule(profile: String) =
        mockMvc.perform(
            post(RULES).contentType(MediaType.APPLICATION_JSON).content("""{"profileId":"$profile"}"""),
        ).andExpect(status().isCreated)

    companion object {
        private const val ALERTS = "/api/v1/alerts"
        private const val RULES = "/api/v1/alerts/rules"
        private const val PREFERENCES = "/api/v1/alerts/preferences"
        private const val PROFILES = "/api/v1/profiles"
        private const val EWA = "0198f0a1-0000-7000-8000-000000000011"
        private const val MAREK = "0198f0a1-0000-7000-8000-000000000012"
        private val marek = user(MAREK).roles("USER")
        private val ID_IN_RESPONSE = Regex("\"id\":\"([^\"]+)\"")

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
