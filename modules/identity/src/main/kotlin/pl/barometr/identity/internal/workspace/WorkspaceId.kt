package pl.barometr.identity.internal.workspace

import java.util.UUID

/** One organisation's account: the thing seats are bought for and policies are set on. */
@JvmInline
value class WorkspaceId(val value: UUID) {
    override fun toString(): String = value.toString()
}
