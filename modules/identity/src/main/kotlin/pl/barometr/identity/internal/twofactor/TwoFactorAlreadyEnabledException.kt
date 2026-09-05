package pl.barometr.identity.internal.twofactor

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * A confirmed second factor is already on this account; turning it off is its own deliberate act.
 */
class TwoFactorAlreadyEnabledException : DomainException(ErrorKind.CONFLICT, "two_factor_already_enabled")
