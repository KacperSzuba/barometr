package pl.barometr.identity.internal.auth

data class AccessToken(val value: String, val expiresInSeconds: Long)
