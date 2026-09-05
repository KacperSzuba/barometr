package pl.barometr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The public API: what anybody gets, what a key changes, and what neither buys.
 *
 * The property worth asserting at this level is the one the whole design rests on — a
 * researcher can `curl` it without registering, and a key raises the *rate* and nothing
 * else. Everything about tiers, scopes and buckets is decided in three different places
 * (identity, platform, the application's filter), and this is where they meet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class PublicApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val json = JsonMapper.builder().build()

    @Test
    fun `anybody may read it, without a key and without an account`() {
        mockMvc.perform(get(CONSULTATIONS))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.consultations").exists())
            // The condition of use, on the response rather than only in a document.
            .andExpect(header().string("X-Attribution", org.hamcrest.Matchers.containsString("Barometr")))
            .andExpect(jsonPath("$.attribution").exists())
    }

    @Test
    fun `every answer says what is left of the rate and when it comes back`() {
        mockMvc.perform(get(CONSULTATIONS))
            .andExpect(status().isOk)
            .andExpect(header().string("X-RateLimit-Limit", "60"))
            .andExpect(header().exists("X-RateLimit-Remaining"))
            .andExpect(header().exists("X-RateLimit-Reset"))
    }

    @Test
    fun `a key raises the rate, and nothing else`() {
        val key = issueKey(scopes = """["read"]""")

        val withKey = mockMvc.perform(get(CONSULTATIONS).header("X-Api-Key", key))
            .andExpect(status().isOk)
            .andExpect(header().string("X-RateLimit-Limit", "600"))
            .andReturn()
            .response
            .contentAsString

        val without = mockMvc.perform(get(CONSULTATIONS)).andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(
            json.readTree(without).get("consultations"),
            json.readTree(withKey).get("consultations"),
            "a tier is a rate, not extra data",
        )
    }

    @Test
    fun `a key nobody issued is refused, and so is a revoked one`() {
        mockMvc.perform(get(CONSULTATIONS).header("X-Api-Key", "brmtr_nie-ma-takiego-klucza"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().json("""{"error":"invalid_api_key"}""", false))

        val account = register()
        val minted = mint(account, """["read"]""")
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("$KEYS/${minted.id}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"),
        ).andExpect(status().isNoContent)

        mockMvc.perform(get(CONSULTATIONS).header("X-Api-Key", minted.secret))
            .andExpect(status().isUnauthorized)
            .andExpect(content().json("""{"error":"invalid_api_key"}""", false))
    }

    /** The whole-dataset download is where a public API stops being cheap to serve. */
    @Test
    fun `the bulk download needs a key with the scope for it`() {
        mockMvc.perform(get("$CONSULTATIONS/csv"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().json("""{"error":"api_key_required"}""", false))

        val readOnly = issueKey(scopes = """["read"]""")
        mockMvc.perform(get("$CONSULTATIONS/csv").header("X-Api-Key", readOnly))
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"error":"api_scope_required"}""", false))

        val bulk = issueKey(scopes = """["read","bulk"]""")
        mockMvc.perform(get("$CONSULTATIONS/csv").header("X-Api-Key", bulk))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id,draft_id,title")))
    }

    @Test
    fun `usage is counted against the key that made it`() {
        val account = register()
        val minted = mint(account, """["read"]""")

        repeat(3) { mockMvc.perform(get(CONSULTATIONS).header("X-Api-Key", minted.secret)).andExpect(status().isOk) }

        mockMvc.perform(get(KEYS).header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].requests").value(3))
            .andExpect(jsonPath("$[0].lastUsedAt").exists())
    }

    @Test
    fun `the key itself comes back once and is never shown again`() {
        val account = register()
        val minted = mint(account, """["read"]""")

        assertTrue(minted.secret.startsWith("brmtr_"), "recognisable in a log or a repository")

        val listed = mockMvc.perform(get(KEYS).header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertTrue(!listed.contains(minted.secret), "there is nowhere to fetch a key from afterwards")
    }

    /**
     * The limit is a real number, not a header: sixty an hour by address is enough to try
     * the API from a terminal and not enough to build on, which is the whole shape of the
     * anonymous tier.
     */
    @Test
    fun `an anonymous caller runs out, and is told when to come back`() {
        val address = "198.51.100.${(2..250).random()}"

        repeat(60) {
            mockMvc.perform(get(CONSULTATIONS).with { it.also { req -> req.remoteAddr = address } })
                .andExpect(status().isOk)
        }

        mockMvc.perform(get(CONSULTATIONS).with { it.also { req -> req.remoteAddr = address } })
            .andExpect(status().isTooManyRequests)
            .andExpect(content().json("""{"error":"rate_limited"}""", false))
            .andExpect(header().string("X-RateLimit-Remaining", "0"))
            .andExpect(header().exists("Retry-After"))
    }

    @Test
    fun `making a key needs an account`() {
        mockMvc.perform(
            post(KEYS).contentType(MediaType.APPLICATION_JSON).content("""{"name":"skrypt"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `a scope nobody defined is refused at the edge`() {
        val account = register()

        mockMvc.perform(
            post(KEYS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"skrypt","scopes":["wszystko"]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().json("""{"error":"unknown_api_scope"}""", false))
    }

    private data class Account(val email: String, val accessToken: String)

    private data class Minted(val id: UUID, val secret: String)

    private fun issueKey(scopes: String): String = mint(register(), scopes).secret

    private fun mint(account: Account, scopes: String): Minted {
        val body = mockMvc.perform(
            post(KEYS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"skrypt","scopes":$scopes}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val node = json.readTree(body)

        return Minted(UUID.fromString(node.get("key").get("id").asString()), node.get("secret").asString())
    }

    private fun register(): Account {
        val email = "publiczne-${UUID.randomUUID()}@example.test"
        val body = mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"correct-horse-battery-staple"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        return Account(email, json.readTree(body).get("accessToken").asString())
    }

    companion object {
        private const val CONSULTATIONS = "/api/v1/public/consultations"
        private const val KEYS = "/api/v1/me/api-keys"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
