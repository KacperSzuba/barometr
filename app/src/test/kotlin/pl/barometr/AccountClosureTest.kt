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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.barometr.testing.PostgresTestDatabase
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The test the specification asks for by name: closing an account really does remove the
 * data, from every system rather than from the one somebody remembered.
 *
 * It has to be at this level. Each context deletes its own rows and is tested where it
 * lives; what nothing else can prove is that *every* context is asked — the orchestration
 * collects whatever implements the port, so the only honest check is to put data in as
 * many places as an account can reach and then count what is left in the database itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class AccountClosureTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val json = JsonMapper.builder().build()
    private val dsl = PostgresTestDatabase.applicationDsl()

    @Test
    fun `closing an account removes what it left in every context`() {
        val account = register()
        val user = subjectOf(account.accessToken)
        fillEveryContext(account)

        assertTrue(rowsAbout(user) > 0, "there is something to delete")

        mockMvc.perform(
            delete(ME)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"password":"$PASSWORD"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.erased[?(@.category=='identity')]").exists())
            .andExpect(jsonPath("$.erased[?(@.category=='profiles')]").exists())
            .andExpect(jsonPath("$.erased[?(@.category=='alerts')]").exists())

        assertEquals(0, rowsAbout(user), "nothing about this account is left in any schema")
    }

    /**
     * The audit trail is the one thing that survives, and the response says so rather
     * than leaving it to be discovered: its entries are hash-chained, and removing one
     * would break the chain for everybody else's.
     */
    @Test
    fun `the audit trail is kept, and the answer says why`() {
        val account = register()
        val user = subjectOf(account.accessToken)
        fillEveryContext(account)

        val body = mockMvc.perform(
            delete(ME)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"password":"$PASSWORD"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.erased[?(@.category=='audit')].kept.audit_event").exists())
            .andReturn()
            .response
            .contentAsString

        assertTrue(body.contains("append-only"), body)
        assertTrue(
            dsl.fetchCount(
                org.jooq.impl.DSL.table("audit.audit_event"),
                org.jooq.impl.DSL.field("actor_id", UUID::class.java).eq(user),
            ) > 0,
            "the entries about this account are still there",
        )
    }

    @Test
    fun `a wrong password does not close anything`() {
        val account = register()
        val user = subjectOf(account.accessToken)

        mockMvc.perform(
            delete(ME)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"password":"nie-to-haslo"}"""),
        ).andExpect(status().isUnauthorized)

        assertTrue(rowsAbout(user) > 0)
    }

    @Test
    fun `a signed-in account can ask for a copy of everything`() {
        val account = register()

        mockMvc.perform(post("$ME/export").header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("requested"))
            .andExpect(jsonPath("$.expiresAt").exists())

        mockMvc.perform(get("$ME/export").header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `somebody else's export is not readable, and is not confirmed to exist`() {
        val account = register()

        mockMvc.perform(
            get("$ME/export/${UUID.randomUUID()}/content")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `neither right is exercised without an account`() {
        mockMvc.perform(post("$ME/export")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            delete(ME).contentType(MediaType.APPLICATION_JSON).content("""{"password":"$PASSWORD"}"""),
        ).andExpect(status().isUnauthorized)
    }

    /** Data in as many places as one account can reach through the API. */
    private fun fillEveryContext(account: Account) {
        val bearer = "Bearer ${account.accessToken}"

        val profile = json.readTree(
            mockMvc.perform(
                post("/api/v1/profiles")
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Budowlanka","interests":[{"kind":"pkd","value":"41.20.Z"}]}"""),
            )
                .andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString,
        ).get("id").asString()

        mockMvc.perform(
            post("/api/v1/alerts/rules")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"profileId":"$profile","stages":[]}"""),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            put("/api/v1/alerts/preferences")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mode":"daily","atHour":8,"zone":"Europe/Warsaw"}"""),
        ).andExpect(status().is2xxSuccessful)

        mockMvc.perform(post("/api/v1/alerts/calendar/subscriptions/$profile").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk)

        mockMvc.perform(
            put("/api/v1/alerts/consultations/${UUID.randomUUID()}/filing")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"note":"uwagi wysłane"}"""),
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/v1/workspaces")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Kancelaria Nowak"}"""),
        ).andExpect(status().isCreated)

        mockMvc.perform(post("$ME/export").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isAccepted)
    }

    /**
     * Every row in every schema that names this account, counted from the database rather
     * than asked of the code that just claimed to have deleted them.
     */
    private fun rowsAbout(user: UUID): Int = OWNED_TABLES.sumOf { (table, column) ->
        dsl.fetchCount(
            org.jooq.impl.DSL.table(table),
            org.jooq.impl.DSL.field(column, UUID::class.java).eq(user),
        )
    }

    private data class Account(val email: String, val accessToken: String)

    /** The subject claim: the account's own identifier, which is what every row names. */
    private fun subjectOf(accessToken: String): UUID {
        val payload = String(
            java.util.Base64.getUrlDecoder().decode(accessToken.split(".")[1]),
            Charsets.UTF_8,
        )

        return UUID.fromString(json.readTree(payload).get("sub").asString())
    }

    private fun register(): Account {
        val email = "zamkniete-${UUID.randomUUID()}@example.test"
        val body = mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        return Account(email, json.readTree(body).get("accessToken").asString())
    }

    companion object {
        private const val ME = "/api/v1/me"
        private const val PASSWORD = "correct-horse-battery-staple"

        /**
         * Every table in every schema that holds a column naming an account, and the
         * column that names it. The audit trail is deliberately absent: it is the one
         * thing that survives, and the test above asserts that it does.
         */
        private val OWNED_TABLES = listOf(
            "identity.users" to "id",
            "identity.session" to "user_id",
            "identity.refresh_tokens" to "user_id",
            "identity.trusted_device" to "user_id",
            "identity.totp_secret" to "user_id",
            "identity.recovery_code" to "user_id",
            "identity.login_challenge" to "user_id",
            "identity.user_roles" to "user_id",
            "identity.workspace_member" to "user_id",
            "identity.data_export" to "user_id",
            "profiles.interest_profile" to "owner_id",
            "alerts.alert_rule" to "owner_id",
            "alerts.delivery_preference" to "owner_id",
            "alerts.notification" to "owner_id",
            "alerts.alert_decision" to "owner_id",
            "alerts.digest" to "owner_id",
            "alerts.email_delivery" to "owner_id",
            "alerts.unsubscribe_token" to "owner_id",
            "alerts.calendar_feed" to "owner_id",
            "alerts.consultation_filing" to "owner_id",
        )

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
