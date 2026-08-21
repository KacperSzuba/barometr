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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase

/**
 * Profiles through the assembled application, where the question is whose data a
 * request reaches.
 *
 * Every route takes its owner from the token and none from the request, so this is the
 * only place that can prove it: the module's own tests pass an owner in, and would
 * still pass if the controller read one from a query parameter.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InterestProfileEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun setUp() {
        // Raw SQL rather than the generated table: reaching into another context's
        // internals is exactly what `ModularityTest` forbids, and a test is not an
        // exemption from it.
        dsl.execute("DELETE FROM profiles.interest_profile")
    }

    @Test
    fun `an anonymous caller is refused`() {
        mockMvc.perform(get(PROFILES)).andExpect(status().isUnauthorized)
    }

    /**
     * A token that verified but whose subject is not one of our identifiers was signed
     * with our key and minted by something else. That is a credential problem, and a
     * 500 would report it as ours.
     */
    @Test
    @WithMockUser(username = "somebody", roles = ["USER"])
    fun `a verified token that names no user of ours is refused, not a server fault`() {
        mockMvc.perform(get(PROFILES))
            .andExpect(status().isUnauthorized)
            .andExpect(content().json("""{"error":"invalid_token"}""", false))
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `an account creates a profile and reads it back`() {
        val id = create("""{"name":"Budowlanka","interests":[{"kind":"pkd","value":"41.20.z"}]}""")

        mockMvc.perform(get("$PROFILES/$id"))
            .andExpect(status().isOk)
            // Normalised on the way in, so what comes back is what will be matched.
            .andExpect(jsonPath("$.interests[0].value").value("41.20.Z"))
            .andExpect(jsonPath("$.version").value(1))

        mockMvc.perform(get(PROFILES)).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `editing the interests writes a new version`() {
        val id = create("""{"name":"Budowlanka","interests":[]}""")

        mockMvc.perform(
            put("$PROFILES/$id/interests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"interests":[{"kind":"keyword","value":"prawo budowlane"}]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))

        mockMvc.perform(get("$PROFILES/$id/versions"))
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a kind nothing could match is refused at the edge`() {
        mockMvc.perform(
            post(PROFILES)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Cokolwiek","interests":[{"kind":"branża","value":"41"}]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().json("""{"error":"invalid_interest"}""", false))
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a nameless profile is a validation failure with the field named`() {
        mockMvc.perform(
            post(PROFILES)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"  ","interests":[]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("validation_failed"))
            .andExpect(jsonPath("$.details.name").exists())
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `the same name twice is a conflict`() {
        create("""{"name":"Budowlanka","interests":[]}""")

        mockMvc.perform(
            post(PROFILES)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Budowlanka","interests":[]}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(content().json("""{"error":"duplicate_profile_name"}""", false))
    }

    /**
     * The test this class exists for. The second account holds a valid token and the
     * identifier of the first one's profile, which is everything an attacker would
     * have.
     *
     * `@WithMockUser` names one account per test method; the second is put on the
     * request itself, which is also what makes it obvious at every call site below
     * which account is asking.
     */
    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `another account cannot reach this one's profile`() {
        val id = create("""{"name":"Budowlanka","interests":[]}""")

        mockMvc.perform(get("$PROFILES/$id").with(marek))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_profile"}""", false))

        mockMvc.perform(
            put("$PROFILES/$id/interests")
                .with(marek)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"interests":[]}"""),
        ).andExpect(status().isNotFound)

        mockMvc.perform(get(PROFILES).with(marek)).andExpect(jsonPath("$.length()").value(0))
    }

    private fun create(body: String): String =
        mockMvc.perform(
            post(PROFILES).contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
            .let { ID_IN_RESPONSE.find(it)!!.groupValues[1] }

    companion object {
        private const val PROFILES = "/api/v1/profiles"
        private const val EWA = "0198f0a1-0000-7000-8000-000000000001"
        private const val MAREK = "0198f0a1-0000-7000-8000-000000000002"
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
