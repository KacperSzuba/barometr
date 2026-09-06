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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.http.MediaType
import pl.barometr.testing.PostgresTestDatabase
import java.util.UUID

/**
 * Who may ask which industries a law is in, and who may decide it.
 *
 * The split is the whole point of the endpoint and it lives in two annotations, which
 * is exactly the kind of thing that breaks without anybody noticing: reading is the
 * product's own description of a public process and any account may have it, while
 * recording a verdict rewrites what every reader is told a law is about and
 * registration is open.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class IndustryLookupEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @WithMockUser(username = "ewa@example.test", roles = ["USER"])
    fun `an ordinary account may ask what a law is about`() {
        mockMvc.perform(get("$SUBJECTS/act/${UUID.randomUUID()}/industries"))
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))
    }

    /** A kind outside the vocabulary is a caller's mistake, and answered as one. */
    @Test
    @WithMockUser(username = "ewa@example.test", roles = ["USER"])
    fun `something that is neither an act nor a draft is refused as a bad request`() {
        mockMvc.perform(get("$SUBJECTS/wniosek/${UUID.randomUUID()}/industries"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `an account nobody signed in cannot ask at all`() {
        mockMvc.perform(get("$SUBJECTS/act/${UUID.randomUUID()}/industries"))
            .andExpect(status().isUnauthorized)
    }

    /** Deciding stays where it was: registration is open, and a tag is what routes alerts. */
    @Test
    @WithMockUser(username = "ewa@example.test", roles = ["USER"])
    fun `an ordinary account cannot record a verdict`() {
        mockMvc.perform(
            put("$SUBJECTS/act/${UUID.randomUUID()}/industries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"industries":[{"pkd":"41.20.Z"}]}"""),
        )
            .andExpect(status().isForbidden)
    }

    private companion object {
        const val SUBJECTS = "/api/v1/taxonomy/subjects"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
