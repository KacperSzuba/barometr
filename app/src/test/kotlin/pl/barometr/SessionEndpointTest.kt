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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where an account is signed in, end to end: real tokens, the real filter chain, the
 * real database.
 *
 * `@WithMockUser` cannot answer the question this feature exists for. The list has to
 * mark the session the request came on, and that mark comes from the `sid` claim of a
 * token identity actually minted — so the test signs in the way a browser does and uses
 * what it gets back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class SessionEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val json = JsonMapper.builder().build()

    @Test
    fun `signing in leaves a device on the list, marked as the one being read on`() {
        val account = register()

        mockMvc.perform(get(SESSIONS).header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].current").value(true))
            .andExpect(jsonPath("$[0].userAgent").value(BROWSER))
            // No address database on a test machine, and the field is present and empty
            // rather than absent: a feature missing is not a feature broken.
            .andExpect(jsonPath("$[0].approximateLocation").doesNotExist())
    }

    @Test
    fun `a second sign-in is a second device, and only one of them is current`() {
        val account = register()
        signIn(account.email)

        val listed = sessions(account.accessToken)

        assertEquals(2, listed.size)
        assertEquals(1, listed.count { it.current }, "exactly one session is the caller's own")
    }

    @Test
    fun `signing out everywhere else leaves the caller signed in`() {
        val account = register()
        signIn(account.email)
        signIn(account.email)

        mockMvc.perform(delete(SESSIONS).header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ended").value(2))

        val left = sessions(account.accessToken)
        assertEquals(1, left.size)
        assertTrue(left.single().current)
    }

    @Test
    fun `a session that is not the caller's cannot be ended, and is not confirmed to exist`() {
        val account = register()

        mockMvc.perform(
            delete("$SESSIONS/${UUID.randomUUID()}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `the device list needs an account`() {
        mockMvc.perform(get(SESSIONS)).andExpect(status().isUnauthorized)
    }

    /** What the session list gives back, as much of it as this test reads. */
    private data class ListedSession(val id: UUID, val current: Boolean)

    private data class Account(val email: String, val accessToken: String)

    private fun sessions(accessToken: String): List<ListedSession> {
        val body = mockMvc.perform(get(SESSIONS).header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        return json.readerForListOf(ListedSession::class.java).readValue(body)
    }

    private fun register(): Account {
        val email = "sesje-${UUID.randomUUID()}@example.test"
        val body = mockMvc.perform(
            post("$AUTH/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.USER_AGENT, BROWSER)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        return Account(email, json.readTree(body).get("accessToken").asString())
    }

    private fun signIn(email: String) {
        mockMvc.perform(
            post("$AUTH/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (iPhone)")
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        ).andExpect(status().isOk)
    }

    companion object {
        private const val AUTH = "/api/v1/auth"
        private const val SESSIONS = "/api/v1/sessions"
        private const val BROWSER = "Mozilla/5.0 (Macintosh)"
        private const val PASSWORD = "correct-horse-battery-staple"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
