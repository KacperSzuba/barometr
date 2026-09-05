package pl.barometr.identity.internal.privacy

import pl.barometr.shared.DomainException
import pl.barometr.shared.ErrorKind

/**
 * No such export to read: never requested, not finished, expired, or somebody else's.
 *
 * One code for all four. An export is the most concentrated collection of a person's data
 * this system holds, so telling an authenticated stranger which identifiers exist is an
 * answer nobody is owed — and none of the four is anything the caller can act on
 * differently.
 */
class UnknownExportException : DomainException(ErrorKind.NOT_FOUND, "unknown_export")
