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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import java.util.UUID

/**
 * The card endpoint answering through the assembled application: who may read it, and
 * what a caller's mistake looks like coming back.
 *
 * The mistake matters as much as the success. An unknown identifier used to be the
 * kind of thing that reached `error(...)` and came back as a server fault (review B8);
 * a `DomainException` carrying `NOT_FOUND` is what makes it a 404 with a stable code
 * instead, and only a running chain proves the mapping is wired.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class DraftCardEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an ordinary account may read a draft card`() {
        mockMvc.perform(get("$DRAFTS/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_draft"}""", false))
    }

    @Test
    fun `an anonymous caller is refused`() {
        mockMvc.perform(get("$DRAFTS/${UUID.randomUUID()}")).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an identifier that is not one is a bad request, not a server fault`() {
        mockMvc.perform(get("$DRAFTS/not-a-uuid")).andExpect(status().isBadRequest)
    }

    companion object {
        private const val DRAFTS = "/api/v1/legislative/drafts"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
