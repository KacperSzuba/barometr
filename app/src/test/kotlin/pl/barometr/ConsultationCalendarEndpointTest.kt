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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import java.util.UUID

/**
 * The calendar answering through the assembled application: who may read it, and what
 * a caller's mistake looks like coming back.
 *
 * Both ends of the window have defaults, so a caller can ask for nothing and get the
 * next month — and the two ways of asking wrongly have to come back as the caller's
 * mistake rather than as a server fault.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class ConsultationCalendarEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an ordinary account may read the calendar, and the window it got is echoed back`() {
        mockMvc.perform(get(CONSULTATIONS).param("from", "2026-05-01").param("until", "2026-05-31"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-05-01"))
            .andExpect(jsonPath("$.until").value("2026-05-31"))
    }

    @Test
    fun `an anonymous caller is refused`() {
        mockMvc.perform(get(CONSULTATIONS)).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `a window that runs backwards is the caller's mistake`() {
        mockMvc.perform(get(CONSULTATIONS).param("from", "2026-05-31").param("until", "2026-05-01"))
            .andExpect(status().isBadRequest)
            .andExpect(content().json("""{"error":"invalid_window"}""", false))
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `a window longer than the calendar answers for is refused`() {
        mockMvc.perform(get(CONSULTATIONS).param("from", "2026-01-01").param("until", "2027-01-01"))
            .andExpect(status().isBadRequest)
            .andExpect(content().json("""{"error":"invalid_window"}""", false))
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `a consultation nothing was opened under is a 404, not a server fault`() {
        mockMvc.perform(get("$CONSULTATIONS/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_consultation"}""", false))
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `an identifier that is not one is a bad request`() {
        mockMvc.perform(get("$CONSULTATIONS/not-a-uuid")).andExpect(status().isBadRequest)
    }

    companion object {
        private const val CONSULTATIONS = "/api/v1/legislative/consultations"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
