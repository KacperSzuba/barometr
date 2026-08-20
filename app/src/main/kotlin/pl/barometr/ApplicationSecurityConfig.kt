package pl.barometr

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import pl.barometr.identity.api.JwtClaims

/**
 * What is protected, decided by the application.
 *
 * How tokens are minted and verified belongs to the identity module, which
 * publishes the `JwtDecoder` this chain consumes. The split matters as modules
 * accumulate: only the application knows every module's routes, and identity
 * has no business holding a list of them.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class ApplicationSecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        // The API authenticates with a Bearer header and reads no cookie, so a
        // cross-site request has nothing to ride on. This would be wrong the
        // moment the chain started trusting a session cookie.
        .csrf { it.disable() }
        // The browser never reaches this service directly; Next.js proxies every
        // call server-to-server, so no CORS negotiation takes place.
        .cors { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers("/auth/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated()
        }
        .oauth2ResourceServer { resourceServer ->
            resourceServer.jwt { it.jwtAuthenticationConverter(jwtAuthenticationConverter()) }
        }
        .exceptionHandling { exceptions ->
            // A plain 401 with a JSON body: the Next.js route guard keys its
            // silent refresh off this status, so it must never be a redirect.
            exceptions.authenticationEntryPoint { _, response, _ ->
                response.writeError(HttpServletResponse.SC_UNAUTHORIZED, "unauthorized")
            }
            exceptions.accessDeniedHandler { _, response, _ ->
                response.writeError(HttpServletResponse.SC_FORBIDDEN, "forbidden")
            }
        }
        .build()

    private fun jwtAuthenticationConverter(): JwtAuthenticationConverter =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter { jwt: Jwt ->
                jwt.getClaimAsStringList(JwtClaims.ROLES)
                    .orEmpty()
                    .map<String, GrantedAuthority> { SimpleGrantedAuthority("ROLE_$it") }
            }
        }

    private fun HttpServletResponse.writeError(statusCode: Int, error: String) {
        status = statusCode
        contentType = MediaType.APPLICATION_JSON_VALUE
        characterEncoding = Charsets.UTF_8.name()
        writer.write("""{"error":"$error"}""")
    }
}
