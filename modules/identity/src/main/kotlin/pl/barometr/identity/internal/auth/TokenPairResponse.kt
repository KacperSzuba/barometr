package pl.barometr.identity.internal.auth

/**
 * Both tokens travel in the response body — this service sets no cookies.
 * Next.js receives them server-side and puts them into HttpOnly cookies on its
 * own domain, so nothing here assumes a browser is on the other end.
 */
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)
