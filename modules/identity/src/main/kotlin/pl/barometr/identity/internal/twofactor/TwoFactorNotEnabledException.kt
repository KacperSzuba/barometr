package pl.barometr.identity.internal.twofactor

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * There is no second factor on this account to confirm, use or turn off.
 */
class TwoFactorNotEnabledException : DomainException(ErrorKind.NOT_FOUND, "two_factor_not_enabled")
