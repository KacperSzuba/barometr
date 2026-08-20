package pl.barometr.identity.internal.auth

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

class EmailAlreadyUsedException :
    DomainException(ErrorKind.CONFLICT, "email_already_used")
