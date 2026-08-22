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
 * The act card through the assembled application: who may read it, and what the two
 * ways of naming an act do when they name nothing.
 *
 * The address route is the one worth asserting here. An ELI has slashes in it, so it is
 * three path segments rather than one, and whether Spring routes `DU/2024/1222` at all
 * is not a question the module's own tests can answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class ActCardEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `an anonymous caller is refused`() {
        mockMvc.perform(get("$ACTS/${UUID.randomUUID()}")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("$ACTS/eli/DU/2024/1222")).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an ordinary account may read an act, by identifier or by address`() {
        mockMvc.perform(get("$ACTS/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_act"}""", false))

        // Routed as three segments, which is the whole reason this route is shaped
        // this way: escaping the slashes would be half-done by half the clients.
        mockMvc.perform(get("$ACTS/eli/DU/2024/1222"))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_act"}""", false))
    }

    /**
     * A typo in an address is a different mistake from an act this archive has not
     * reached, and comes back as a different status — the same way a malformed
     * identifier already does on the sibling route.
     */
    @Test
    @WithMockUser(roles = ["USER"])
    fun `an address that is not one is a bad request, not a missing act`() {
        mockMvc.perform(get("$ACTS/eli/DZIENNIKUSTAW/2024/1222"))
            .andExpect(status().isBadRequest)
            .andExpect(content().json("""{"error":"invalid_act_address"}""", false))

        // A year that is not a number never reaches the handler at all.
        mockMvc.perform(get("$ACTS/eli/DU/dwa-tysiace/1222")).andExpect(status().isBadRequest)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an identifier that is not one is a bad request, not a server fault`() {
        mockMvc.perform(get("$ACTS/nie-jest-uuidem")).andExpect(status().isBadRequest)
    }

    companion object {
        private const val ACTS = "/api/v1/legislative/acts"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
