package pl.barometr.identity.internal.auth

import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import pl.barometr.identity.internal.config.JwtClaims
import pl.barometr.identity.internal.config.JwtProperties
import pl.barometr.identity.internal.user.UserEntity
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.Instant

data class AccessToken(val value: String, val expiresInSeconds: Long)

@Service
class TokenService(
    private val encoder: JwtEncoder,
    private val properties: JwtProperties,
    private val clock: Clock,
) {

    /**
     * Nothing sensitive belongs in these claims — a JWT payload is plain base64
     * and readable by anyone holding the token.
     */
    fun createAccessToken(user: UserEntity): AccessToken {
        val issuedAt = clock.instant()

        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .audience(listOf(properties.audience))
            .subject(user.id.toString())
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(properties.accessTtl))
            .id(Ids.next().toString())
            .claim(JwtClaims.EMAIL, user.email)
            .claim(JwtClaims.ROLES, user.roleNames.toList())
            .build()

        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims))

        return AccessToken(token.tokenValue, properties.accessTtl.seconds)
    }
}
