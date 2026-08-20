package pl.barometr.identity.internal.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Identity owns *how* tokens are minted and verified. It does not own *what is
 * protected* — the filter chain listing public and private routes lives in the
 * application, which is the only place that knows every module's routes.
 *
 * HS256 for now; move to RS256 with a published JWKS once more than one service
 * needs to verify these, since verifiers should not have to hold the signing key.
 */
@Configuration
class JwtConfig(private val properties: JwtProperties) {

    private val key: SecretKey =
        SecretKeySpec(properties.secret.toByteArray(StandardCharsets.UTF_8), HMAC_SHA256)

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))

    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
            .apply {
                setJwtValidator(
                    DelegatingOAuth2TokenValidator(
                        // Covers exp, nbf and iss — but NOT aud, which is the check
                        // most often left out by accident.
                        JwtValidators.createDefaultWithIssuer(properties.issuer),
                        audienceValidator(),
                    ),
                )
            }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(BCRYPT_STRENGTH)

    private fun audienceValidator(): OAuth2TokenValidator<Jwt> = OAuth2TokenValidator { jwt ->
        if (properties.audience in jwt.audience.orEmpty()) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error(
                    "invalid_token",
                    "Required audience '${properties.audience}' is missing",
                    null,
                ),
            )
        }
    }

    private companion object {
        const val HMAC_SHA256 = "HmacSHA256"
        const val BCRYPT_STRENGTH = 12
    }
}
