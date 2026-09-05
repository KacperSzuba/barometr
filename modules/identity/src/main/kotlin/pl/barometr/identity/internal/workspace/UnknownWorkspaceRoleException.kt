package pl.barometr.identity.internal.workspace

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Something that is not one of the three roles a workspace has.
 *
 * A caller's mistake reported as one: `error(...)` here would answer a typo with a
 * server fault, which is what `DomainException` exists to prevent.
 */
class UnknownWorkspaceRoleException(role: String) : DomainException(ErrorKind.INVALID, "unknown_workspace_role") {
    init {
        addSuppressed(IllegalArgumentException("no workspace role '$role'"))
    }
}
