package pl.barometr

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
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
import kotlin.test.assertTrue

/**
 * The trail, filled by the application rather than by anybody remembering to call it.
 *
 * Only a running chain can prove the two things that matter here: that a refusal is
 * recorded at all — the filter sits after the security chain precisely so it can see
 * one — and that a successful read is not, because a log of page views buries the entry
 * somebody is looking for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class AuditTrailEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    /**
     * The entry an audit log is bought for: somebody reaching for what is not theirs
     * and being stopped. A `GET` at that, which the filter records only because it was
     * refused.
     */
    @Test
    fun `a refusal is recorded, even for a read`() {
        val before = recorded()

        mockMvc.perform(get("/api/v1/alerts/rules")).andExpect(status().isUnauthorized)

        val denial = recordedSince(before).first { it["resource"] == "/api/v1/alerts/rules" }
        assertTrue(denial["outcome"] == "denied", "a 401 is a denial: $denial")
        assertTrue(denial["status"] == 401)
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `an operator endpoint refused to an ordinary account is recorded as denied`() {
        val before = recorded()

        mockMvc.perform(post("/api/v1/search/index/rebuild").with(csrfNothing()))
            .andExpect(status().isForbidden)

        val denial = recordedSince(before).first { it["resource"] == "/api/v1/search/index/rebuild" }
        assertTrue(denial["outcome"] == "denied")
        assertTrue(denial["actor_id"].toString() == EWA, "the account that was refused is named")
    }

    /**
     * A successful read is not an event. The log would otherwise be page views with the
     * occasional refusal buried in it, which is the log nobody searches twice.
     */
    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `a successful read is not recorded, and a change is`() {
        val before = recorded()

        mockMvc.perform(get("/api/v1/profiles")).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/v1/profiles").contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Audytowana","interests":[]}"""),
        ).andExpect(status().isCreated)

        val since = recordedSince(before)
        assertTrue(since.none { it["action"] == "GET" }, "a successful read is not an event: $since")
        assertTrue(
            since.any { it["action"] == "POST" && it["outcome"] == "succeeded" },
            "a change is: $since",
        )
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `an account reads its own history and nobody else's`() {
        mockMvc.perform(
            post("/api/v1/profiles").contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Historia ${System.nanoTime()}","interests":[]}"""),
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/api/v1/audit/me"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].action").value("POST"))
            .andExpect(jsonPath("$[0].hash").isNotEmpty)

        // A second account sees its own, which here is what its own refusals produced.
        mockMvc.perform(get("/api/v1/audit/me").with(user(MAREK).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.resource == '/api/v1/profiles')].length()").doesNotExist())
    }

    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `the history exports as csv a person can keep`() {
        val csv = mockMvc.perform(get("/api/v1/audit/me.csv"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andReturn().response.contentAsString

        assertTrue(csv.startsWith("at,action,resource,outcome,status"), csv.take(80))
    }

    /** Reading the whole trail is an operator's question, not everybody's. */
    @Test
    @WithMockUser(username = EWA, roles = ["USER"])
    fun `an ordinary account cannot ask whether the trail is intact`() {
        mockMvc.perform(get("/api/v1/audit/integrity")).andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = EWA, roles = ["OPERATOR"])
    fun `an operator can, and the answer is that it is`() {
        mockMvc.perform(get("/api/v1/audit/integrity"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.intact").value(true))
    }

    private fun recorded(): Long =
        dsl.fetchOne("SELECT coalesce(max(sequence), 0) AS s FROM audit.audit_event")!!
            .get("s", Long::class.java)!!

    private fun recordedSince(sequence: Long): List<Map<String, Any?>> =
        dsl.fetch("SELECT * FROM audit.audit_event WHERE sequence > ? ORDER BY sequence", sequence)
            .map { it.intoMap() }

    /** The chain reads no cookie, so there is nothing to send; this keeps that visible. */
    private fun csrfNothing() = user(EWA).roles("USER")

    companion object {
        private const val EWA = "0198f0a1-0000-7000-8000-00000000a1d1"
        private const val MAREK = "0198f0a1-0000-7000-8000-00000000a1d2"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
