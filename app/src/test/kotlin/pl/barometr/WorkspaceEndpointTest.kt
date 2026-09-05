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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertTrue

/**
 * An organisation's account through the assembled application: seats, roles, an
 * invitation somebody takes, and the policy that stops a member reaching anything until
 * they have set a second factor up.
 *
 * The last one is the reason the rest exists, and it can only be asserted here: the claim
 * is minted by identity and acted on by a filter in the application, and neither half
 * proves anything about the other on its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class WorkspaceEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val json = JsonMapper.builder().build()

    @Test
    fun `whoever creates a workspace owns it and can see its seats`() {
        val ewa = register()

        val workspace = createWorkspace(ewa, "Kancelaria Nowak")

        mockMvc.perform(get("$WORKSPACES/$workspace").header(HttpHeaders.AUTHORIZATION, "Bearer ${ewa.accessToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Kancelaria Nowak"))
            .andExpect(jsonPath("$.myRole").value("owner"))
            .andExpect(jsonPath("$.seatsTaken").value(1))
    }

    @Test
    fun `somebody who is not in it is not told it exists`() {
        val ewa = register()
        val obcy = register()
        val workspace = createWorkspace(ewa, "Kancelaria Nowak")

        mockMvc.perform(get("$WORKSPACES/$workspace").header(HttpHeaders.AUTHORIZATION, "Bearer ${obcy.accessToken}"))
            .andExpect(status().isNotFound)
            .andExpect(content().json("""{"error":"unknown_workspace"}""", false))
    }

    @Test
    fun `an invitation is offered, taken, and turns into a seat`() {
        val ewa = register()
        val marek = register()
        val workspace = createWorkspace(ewa, "Kancelaria Nowak")

        mockMvc.perform(
            post("$WORKSPACES/$workspace/invitations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ewa.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"${marek.email}","role":"admin"}"""),
        )
            .andExpect(status().isCreated)
            // The token is not in the response: it goes out in one message, to one
            // address, and lives here only as a hash.
            .andExpect(jsonPath("$.token").doesNotExist())
            .andExpect(jsonPath("$.email").value(marek.email))

        // The link is what the invited person receives; the test reads it from the queued
        // message the way a mail client would read the mail.
        val token = invitationTokenFor(marek.email)

        mockMvc.perform(
            post("$WORKSPACES/invitations/$token/acceptance")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${marek.accessToken}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("admin"))

        mockMvc.perform(get("$WORKSPACES/$workspace/members").header(HttpHeaders.AUTHORIZATION, "Bearer ${ewa.accessToken}"))
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `an invitation sent to one address does not work for another account`() {
        val ewa = register()
        val marek = register()
        val obcy = register()
        val workspace = createWorkspace(ewa, "Kancelaria Nowak")
        invite(ewa, workspace, marek.email)

        mockMvc.perform(
            post("$WORKSPACES/invitations/${invitationTokenFor(marek.email)}/acceptance")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${obcy.accessToken}"),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"error":"invitation_not_for_this_account"}""", false))
    }

    @Test
    fun `an ordinary member cannot invite anybody`() {
        val ewa = register()
        val marek = register()
        val workspace = createWorkspace(ewa, "Kancelaria Nowak")
        invite(ewa, workspace, marek.email, role = "member")
        mockMvc.perform(
            post("$WORKSPACES/invitations/${invitationTokenFor(marek.email)}/acceptance")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${marek.accessToken}"),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("$WORKSPACES/$workspace/invitations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${marek.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"trzeci@example.test","role":"member"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"error":"not_workspace_administrator"}""", false))
    }

    /**
     * What the specification means by "enforcement at workspace level blocks access until
     * it is configured": the member is signed in, can reach the enrolment routes, and can
     * reach nothing else until they have enrolled.
     */
    @Test
    fun `a workspace that insists on a second factor lets a member in only far enough to set one up`() {
        val ewa = register()
        val workspace = createWorkspace(ewa, "Kancelaria Nowak")

        mockMvc.perform(
            put("$WORKSPACES/$workspace/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ewa.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"requireTwoFactor":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.requireTwoFactor").value(true))

        // The token minted before the policy existed still works: the claim is decided
        // when a token is minted, and this one says nothing about enrolling.
        val after = signIn(ewa.email)

        mockMvc.perform(get(PROFILES).header(HttpHeaders.AUTHORIZATION, "Bearer $after"))
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"error":"two_factor_setup_required"}""", false))

        mockMvc.perform(get("/api/v1/auth/2fa").header(HttpHeaders.AUTHORIZATION, "Bearer $after"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer $after"))
            .andExpect(status().isOk)
    }

    @Test
    fun `seats cannot be sold back below what is in use`() {
        val ewa = register()
        val marek = register()
        val workspace = createWorkspace(ewa, "Kancelaria Nowak")
        invite(ewa, workspace, marek.email)

        // Two seats spoken for: one member and one invitation still open.
        mockMvc.perform(
            put("$WORKSPACES/$workspace/seats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ewa.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seats":1}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(content().json("""{"error":"no_seats_left"}""", false))

        // And a number nobody could mean is refused at the edge, with the field named.
        mockMvc.perform(
            put("$WORKSPACES/$workspace/seats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${ewa.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seats":0}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a workspace needs an account`() {
        mockMvc.perform(get(WORKSPACES)).andExpect(status().isUnauthorized)
    }

    private data class Account(val email: String, val accessToken: String)

    private fun createWorkspace(owner: Account, name: String): UUID {
        val body = mockMvc.perform(
            post(WORKSPACES)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${owner.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        return UUID.fromString(json.readTree(body).get("id").asString())
    }

    private fun invite(owner: Account, workspace: UUID, email: String, role: String = "member") {
        mockMvc.perform(
            post("$WORKSPACES/$workspace/invitations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${owner.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","role":"$role"}"""),
        ).andExpect(status().isCreated)
    }

    /**
     * The token from the queued invitation message.
     *
     * There is nowhere else it could come from, and that is the design: the API never
     * returns it. Reading the job's payload is this test standing in for the mail client
     * on the other end.
     */
    private fun invitationTokenFor(email: String): String {
        val payload = PostgresTestDatabase.applicationDsl()
            .fetch(
                "select payload::text from platform.job where type = 'alerts.invitation-mail' " +
                    "and payload::text like ? order by created_at desc limit 1",
                "%$email%",
            )
            .first()
            .get(0, String::class.java)

        val url = json.readTree(payload).get("acceptUrl").asString()
        assertTrue(url.contains("/zaproszenia/"), url)

        return url.substringAfterLast('/')
    }

    private fun register(): Account {
        val email = "zespol-${UUID.randomUUID()}@example.test"
        val body = mockMvc.perform(
            post("$AUTH/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        return Account(email, json.readTree(body).get("accessToken").asString())
    }

    private fun signIn(email: String): String {
        val body = mockMvc.perform(
            post("$AUTH/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        return json.readTree(body).get("accessToken").asString()
    }

    companion object {
        private const val AUTH = "/api/v1/auth"
        private const val WORKSPACES = "/api/v1/workspaces"
        private const val PROFILES = "/api/v1/profiles"
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
