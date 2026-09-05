package pl.barometr

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
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
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A second factor, end to end: enrol, sign in with a password, answer with a code.
 *
 * The test plays the authenticator, because nothing else can — the API deliberately
 * never hands out a code, only the secret one is derived from. What that buys is the
 * only test in the suite that proves the whole exchange works against the real chain:
 * a password alone gets `202` and a challenge, and the challenge plus six digits gets
 * tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class TwoFactorLoginTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val json = JsonMapper.builder().build()
    private val authenticator = TimeBasedOneTimePasswordGenerator()

    @Test
    fun `with no second factor a password is enough`() {
        val account = register()

        mockMvc.perform(login(account.email))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())
    }

    @Test
    fun `enrolling changes nothing until a code confirms it`() {
        val account = register()
        beginEnrolment(account.accessToken)

        mockMvc.perform(get(TWO_FACTOR).header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.enrolmentStarted").value(true))

        mockMvc.perform(login(account.email)).andExpect(status().isOk)
    }

    @Test
    fun `once confirmed, a password buys a challenge and the code buys the tokens`() {
        val account = register()
        val recovery = enable(account.accessToken)

        assertEquals(10, recovery.codes.size)

        val challenge = mockMvc.perform(login(account.email))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.twoFactorRequired").value(true))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andReturn()
            .response
            .contentAsString
            .let { json.readTree(it).get("challengeId").asString() }

        mockMvc.perform(
            post("$AUTH/login/2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"challengeId":"$challenge","code":"${codeFor(recovery.secret)}"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
    }

    @Test
    fun `a wrong code does not sign anybody in`() {
        val account = register()
        enable(account.accessToken)

        val challenge = challengeFrom(login(account.email))

        mockMvc.perform(
            post("$AUTH/login/2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"challengeId":"$challenge","code":"000000"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_two_factor_code"))
    }

    @Test
    fun `a recovery code gets somebody in whose phone is gone`() {
        val account = register()
        val recovery = enable(account.accessToken)

        val challenge = challengeFrom(login(account.email))

        mockMvc.perform(
            post("$AUTH/login/2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"challengeId":"$challenge","code":"${recovery.codes.first()}"}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(get(TWO_FACTOR).header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"))
            .andExpect(jsonPath("$.recoveryCodesLeft").value(9))
    }

    @Test
    fun `turning it off takes a code the caller can still produce`() {
        val account = register()
        val recovery = enable(account.accessToken)

        mockMvc.perform(
            delete(TWO_FACTOR)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"000000"}"""),
        ).andExpect(status().isUnauthorized)

        mockMvc.perform(
            delete(TWO_FACTOR)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"${codeFor(recovery.secret)}"}"""),
        ).andExpect(status().isNoContent)

        mockMvc.perform(login(account.email)).andExpect(status().isOk)
    }

    /**
     * The bargain, end to end: answering the factor once and asking to be remembered
     * means the next sign-in from the same browser needs the password alone — and the
     * token is a credential, so it comes back only when it was asked for.
     */
    @Test
    fun `a remembered device signs in with the password alone`() {
        val account = register()
        val recovery = enable(account.accessToken)
        val challenge = challengeFrom(login(account.email))

        val deviceToken = json.readTree(
            mockMvc.perform(
                post("$AUTH/login/2fa")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"challengeId":"$challenge","code":"${codeFor(recovery.secret)}","rememberDevice":true}"""),
            )
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString,
        ).get("deviceToken").asString()

        mockMvc.perform(
            post("$AUTH/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"${account.email}","password":"$PASSWORD","deviceToken":"$deviceToken"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())
    }

    @Test
    fun `without asking to be remembered, no device token comes back`() {
        val account = register()
        val recovery = enable(account.accessToken)
        val challenge = challengeFrom(login(account.email))

        mockMvc.perform(
            post("$AUTH/login/2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"challengeId":"$challenge","code":"${codeFor(recovery.secret)}"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceToken").doesNotExist())
    }

    @Test
    fun `forgetting the devices asks for a code again`() {
        val account = register()
        val recovery = enable(account.accessToken)
        val challenge = challengeFrom(login(account.email))

        val deviceToken = json.readTree(
            mockMvc.perform(
                post("$AUTH/login/2fa")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"challengeId":"$challenge","code":"${codeFor(recovery.secret)}","rememberDevice":true}"""),
            ).andReturn().response.contentAsString,
        ).get("deviceToken").asString()

        mockMvc.perform(
            get("$TWO_FACTOR/trusted-devices").header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))

        mockMvc.perform(
            delete("$TWO_FACTOR/trusted-devices").header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.forgotten").value(1))

        mockMvc.perform(
            post("$AUTH/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"${account.email}","password":"$PASSWORD","deviceToken":"$deviceToken"}"""),
        ).andExpect(status().isAccepted)
    }

    @Test
    fun `an ordinary account cannot reset somebody else's second factor`() {
        val account = register()

        mockMvc.perform(
            delete("/api/v1/operator/users/${UUID.randomUUID()}/2fa")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}"),
        ).andExpect(status().isForbidden)
    }

    private data class Account(val email: String, val accessToken: String)

    private data class Enrolment(val secret: String, val codes: List<String>)

    private fun login(email: String) = post("$AUTH/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"email":"$email","password":"$PASSWORD"}""")

    private fun challengeFrom(request: org.springframework.test.web.servlet.RequestBuilder): String =
        json.readTree(mockMvc.perform(request).andReturn().response.contentAsString)
            .get("challengeId")
            .asString()

    private fun register(): Account {
        val email = "dwa-skladniki-${UUID.randomUUID()}@example.test"
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

    private fun beginEnrolment(accessToken: String): String =
        json.readTree(
            mockMvc.perform(post(TWO_FACTOR).header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString,
        ).get("secret").asString()

    private fun enable(accessToken: String): Enrolment {
        val secret = beginEnrolment(accessToken)
        val body = mockMvc.perform(
            post("$TWO_FACTOR/confirmation")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"${codeFor(secret)}"}"""),
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val listed = json.readTree(body).get("recoveryCodes")
        val codes = (0 until listed.size()).map { index -> listed.get(index).asString() }
        assertTrue(codes.isNotEmpty())

        return Enrolment(secret, codes)
    }

    /** What the phone would be showing right now. */
    private fun codeFor(secret: String): String {
        val key = SecretKeySpec(decodeBase32(secret), authenticator.algorithm)

        return "%06d".format(authenticator.generateOneTimePassword(key, Instant.now()))
    }

    private fun decodeBase32(secret: String): ByteArray {
        val out = mutableListOf<Byte>()
        var buffer = 0
        var bits = 0

        secret.trim().uppercase().forEach { character ->
            buffer = (buffer shl 5) or BASE32.indexOf(character)
            bits += 5
            if (bits >= 8) {
                out += ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }

        return out.toByteArray()
    }

    companion object {
        private const val AUTH = "/api/v1/auth"
        private const val TWO_FACTOR = "/api/v1/auth/2fa"
        private const val PASSWORD = "correct-horse-battery-staple"
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
