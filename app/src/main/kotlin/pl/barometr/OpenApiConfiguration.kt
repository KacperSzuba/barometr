package pl.barometr

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The API's contract, generated from the controllers rather than written beside them.
 *
 * Written by hand, a contract is a second description of the same thing, and the two
 * disagree the first time somebody adds a field in a hurry. Generated, it is wrong only
 * when the code is. The web application generates its response types from the
 * `openapi.json` this produces, which is what keeps a Kotlin `data class` and a
 * TypeScript interface from drifting apart.
 *
 * Declared in the application because only the application knows every context's
 * routes — the same reason the security chain is here rather than in identity.
 *
 * **What is not here.** No per-endpoint annotations: springdoc reads the mappings and
 * the Kotlin types, which is where the truth already is, and a `@Operation` restating
 * a method name is one more thing to leave stale. What cannot be read from the code is
 * declared once below: that every route outside `/api/v1/auth` needs a bearer token,
 * and what the token is.
 */
@Configuration
class OpenApiConfiguration {

    @Bean
    fun barometrApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Barometr API")
                .version(CONTRACT_VERSION)
                .description(
                    "Ingestion, the legislative tracker, interest profiles and alerts. " +
                        "Every response type is generated from the controllers; a field " +
                        "is added without a version, and removed only after a stated " +
                        "transition period.",
                ),
        )
        .components(
            Components().addSecuritySchemes(
                BEARER,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("The access token from POST /api/v1/auth/login, short-lived by design."),
            ),
        )
        // Applied to everything, because everything except signing in and the two
        // capability URLs requires it. A scheme declared and never required is a
        // contract that says the API is open.
        .addSecurityItem(SecurityRequirement().addList(BEARER))

    private companion object {
        /**
         * The version of the *contract*, not of the build. It changes when the shape of
         * a response does in a way a client has to be told about — which is what the
         * `/api/v1` prefix already says, and this is where it is said in the document.
         */
        const val CONTRACT_VERSION = "1.0"

        const val BEARER = "bearer-jwt"
    }
}
