package pl.barometr

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `an account sees its own alerts and its own decisions, and nobody else's`() {
        mockMvc.perform(get(ALERTS)).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
        mockMvc.perform(get("$ALERTS/decisions")).andExpect(status().isOk)
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
