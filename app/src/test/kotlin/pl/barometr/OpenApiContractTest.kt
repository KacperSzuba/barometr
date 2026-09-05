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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * The contract, generated and written to a file the build publishes.
 *
 * Two jobs, and the second is the point. The assertions below check that the document
 * describes the API rather than half of it — a controller that stops being scanned, or
 * a springdoc upgrade that quietly changes the path, would otherwise be found by a
 * frontend build failing on missing types.
 *
 * And the run leaves `build/openapi/openapi.json` behind, which is what CI uploads and
 * what the web application generates its TypeScript from. Produced by a test rather
 * than by starting the application and curling it: the context here already has a
 * migrated database, and a pipeline step that boots a server, waits for a port and
 * hopes is the kind of step that fails on a slow morning.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class OpenApiContractTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @WithMockUser(roles = ["USER"])
    fun `the contract describes every context's routes, and is written where the build can publish it`() {
        val contract = mockMvc.perform(get(API_DOCS))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        listOf(
            "/api/v1/auth/login",
            "/api/v1/auth/login/2fa",
            "/api/v1/auth/2fa",
            "/api/v1/sessions",
            "/api/v1/workspaces",
            "/api/v1/workspaces/{id}/invitations",
            "/api/v1/auth/2fa/trusted-devices",
            "/api/v1/legislative/drafts/{id}",
            "/api/v1/legislative/consultations",
            "/api/v1/corpus/documents/{documentId}/changes",
            "/api/v1/alerts/rules",
            "/api/v1/alerts/calendar/feed/{token}.ics",
            "/api/v1/profiles",
            "/api/v1/search",
            "/api/v1/taxonomy/review",
            "/api/v1/ingestion/backfill",
        ).forEach { route ->
            assertContains(contract, "\"$route\"", message = "the contract says nothing about $route")
        }

        // What cannot be read from the code, and is declared once: how a caller
        // authenticates, and that everything requires it.
        assertContains(contract, "bearer-jwt")

        Files.createDirectories(OUTPUT.parent)
        Files.writeString(OUTPUT, contract)
        assertTrue(Files.size(OUTPUT) > 0, "an empty contract is worse than none")
    }

    /**
     * Signing in is the one route a caller has before they have a token, and the
     * document is generated from the same mappings the chain permits — so a rename that
     * moved one and not the other would be visible here.
     */
    @Test
    fun `the contract is not readable without an account`() {
        mockMvc.perform(get(API_DOCS)).andExpect(status().isUnauthorized)
    }

    companion object {
        private const val API_DOCS = "/v3/api-docs"

        /** Where CI picks it up; see .github/workflows/backend.yml. */
        private val OUTPUT: Path = Path.of("build", "openapi", "openapi.json")

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
