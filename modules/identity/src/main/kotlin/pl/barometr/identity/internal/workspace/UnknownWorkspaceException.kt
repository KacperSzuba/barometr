package pl.barometr.identity.internal.workspace

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No such workspace, or none this account is in. One code for both: a caller can do nothing different with either, and confirming which workspaces exist is an answer nobody is owed.
 */
class UnknownWorkspaceException : DomainException(ErrorKind.NOT_FOUND, "unknown_workspace")
