package pl.barometr.identity.internal.auth

import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import pl.barometr.identity.api.JwtClaims
import pl.barometr.identity.api.Role
import pl.barometr.identity.internal.config.JwtProperties
import pl.barometr.identity.internal.user.User
import pl.barometr.shared.Ids
import java.time.Clock
import java.util.UUID

@Service
class TokenService(
    private val encoder: JwtEncoder,
    private val properties: JwtProperties,
    private val clock: Clock,
) {

    /**
     * Nothing sensitive belongs in these claims — a JWT payload is plain base64
     * and readable by anyone holding the token.
     *
     * [sessionId] is the refresh-token family this token descends from, carried so that
     * the device list can say which of the sessions is the one being read on. It
     * identifies a login, not a person, and is useless without the token it sits in.
     *
     * [mustEnrolTwoFactor] is the workspace policy the application's filter chain acts
     * on: a caller whose workspace insists on a second factor they have not set up is
     * signed in and may reach the enrolment routes and nothing else.
     */
    fun createAccessToken(user: User, sessionId: UUID, mustEnrolTwoFactor: Boolean = false): AccessToken {
        val issuedAt = clock.instant()

        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .audience(listOf(properties.audience))
            .subject(user.id.toString())
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(properties.accessTtl))
            .id(Ids.next().toString())
            .claim(JwtClaims.EMAIL, user.email)
            .claim(JwtClaims.ROLES, user.roles.map(Role::name))
            .claim(JwtClaims.SESSION, sessionId.toString())
            .claim(JwtClaims.ENROLMENT_REQUIRED, mustEnrolTwoFactor)
            .build()

        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims))

        return AccessToken(token.tokenValue, properties.accessTtl.seconds)
    }
}
