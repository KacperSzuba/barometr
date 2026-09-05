package pl.barometr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import java.util.UUID

/**
 * That the endpoints only an operator may call are only callable by an operator.
 *
 * Registration is open, so "authenticated" means "anyone who signed up". Behind these
 * two routes are a multi-week crawl of somebody else's server and the power to decide
 * which law a user is shown — and both are guarded by one annotation each, which is
 * exactly the kind of guard that disappears in a refactor without anything failing.
 *
 * Asserted through the real filter chain and the real method-security interceptor:
 * testing the annotation's presence would prove only that a string is still written
 * where somebody once wrote it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class OperatorEndpointAccessTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an ordinary account cannot read the act match queue`() {
        mockMvc.perform(get(ACT_MATCHES)).andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an ordinary account cannot start a replay of a public register`() {
        mockMvc.perform(get("$INGESTION/completeness").param("connector", "isap"))
            .andExpect(status().isForbidden)
    }

    /**
     * Rebuilding the search index cannot lose anything — the index holds only what is
     * derived — but it walks every act and draft there is and writes them all, which is
     * somebody's afternoon of I/O for the asking.
     */
    @Test
    @WithMockUser(roles = ["USER"])
    fun `an ordinary account cannot rebuild the search index`() {
        mockMvc.perform(post("$SEARCH_INDEX/rebuild")).andExpect(status().isForbidden)
    }

    /**
     * A verdict here decides who is told about a bill. Somebody who could write one
     * could put a competitor's industry on an act and change what lands in their inbox,
     * and registration is open.
     */
    @Test
    @WithMockUser(roles = ["USER"])
    fun `an ordinary account cannot decide what industry a law concerns`() {
        mockMvc.perform(get("$TAXONOMY/review")).andExpect(status().isForbidden)

        mockMvc.perform(
            put("$TAXONOMY/subjects/draft/${UUID.randomUUID()}/industries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"industries":[{"pkd":"41.20.Z"}]}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(roles = ["OPERATOR"])
    fun `an operator reads the industry review queue`() {
        mockMvc.perform(get("$TAXONOMY/review")).andExpect(status().isOk)
    }

    @Test
    fun `an anonymous caller is refused before authorization is even considered`() {
        mockMvc.perform(get(ACT_MATCHES)).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(roles = ["OPERATOR"])
    fun `an operator reads the queue`() {
        mockMvc.perform(get(ACT_MATCHES)).andExpect(status().isOk)
    }

    companion object {
        private const val ACT_MATCHES = "/api/v1/legislative/act-matches"
        private const val INGESTION = "/api/v1/ingestion"
        private const val SEARCH_INDEX = "/api/v1/search/index"
        private const val TAXONOMY = "/api/v1/taxonomy"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
