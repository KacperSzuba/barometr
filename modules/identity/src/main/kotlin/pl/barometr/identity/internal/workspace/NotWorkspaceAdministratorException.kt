package pl.barometr.identity.internal.workspace

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * Only an owner or an administrator may invite, remove or set policy. Forbidden rather than not-found, deliberately: the caller is a member and can see the workspace, so pretending it is absent would be a lie they can check.
 */
class NotWorkspaceAdministratorException : DomainException(ErrorKind.FORBIDDEN, "not_workspace_administrator")
