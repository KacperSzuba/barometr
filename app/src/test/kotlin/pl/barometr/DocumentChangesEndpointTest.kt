package pl.barometr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import java.util.UUID

/**
 * What changed between two versions of a document, through the assembled application.
 *
 * Two things are asserted here that the module's own tests cannot: that reading the
 * changes needs an account and nothing more, and that queueing a comparison needs an
 * operator. The second is minutes of parsing over documents that run to three hundred
 * pages, which is the same reason a replay is not something anybody who signs up may
 * start.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class DocumentChangesEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `an anonymous caller is refused`() {
        mockMvc.perform(get(changesOf(UUID.randomUUID()))).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an ordinary account may ask for a document's changes`() {
        mockMvc.perform(get(changesOf(UUID.randomUUID())))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_comparison"}""", false))
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `a page nobody can render is refused rather than quietly resized`() {
        mockMvc.perform(get(changesOf(UUID.randomUUID())).param("limit", "5000"))
            .andExpect(status().isBadRequest)
            .andExpect(content().json("""{"error":"invalid_change_page"}""", false))
    }

    /**
     * Naming one version and not the other is not a pair, and answering it with the
     * newest comparison would be answering a question nobody asked.
     */
    @Test
    @WithMockUser(roles = ["USER"])
    fun `half a pair is a bad request`() {
        mockMvc.perform(get(changesOf(UUID.randomUUID())).param("from", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest)
            .andExpect(content().json("""{"error":"invalid_change_page"}""", false))
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an ordinary account cannot queue a comparison`() {
        mockMvc.perform(post("${changesOf(UUID.randomUUID())}/queue")).andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(roles = ["OPERATOR"])
    fun `an operator queues what a document is missing, and gets nothing for a document with no versions`() {
        mockMvc.perform(post("${changesOf(UUID.randomUUID())}/queue"))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"pairs":0,"queued":0}""", false))
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an identifier that is not one is a bad request, not a server fault`() {
        mockMvc.perform(get("/api/v1/corpus/documents/nie-jest-uuidem/changes")).andExpect(status().isBadRequest)
    }

    companion object {
        private fun changesOf(documentId: UUID) = "/api/v1/corpus/documents/$documentId/changes"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
