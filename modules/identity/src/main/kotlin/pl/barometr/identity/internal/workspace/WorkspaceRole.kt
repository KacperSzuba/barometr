package pl.barometr.identity.internal.workspace

/**
 * What somebody may do to their own organisation's account, matching the `CHECK` on
 * `workspace_member.role`.
 *
 * **Not an application role.** `identity.user_roles` says what somebody may do to this
 * *system* — an `OPERATOR` starts crawls of public registries. This says what they may
 * do to their own workspace. Keeping the two vocabularies apart is what stops the
 * administrator of one customer's workspace from becoming an administrator of the
 * product.
 *
 * Three of them, because the difference that matters is between "can change the bill and
 * the policies" and "can add people", and one workspace must always have somebody who
 * cannot be removed by an argument between colleagues.
 */
enum class WorkspaceRole(val wireName: String) {
    /** Bought it, pays for it, and is the last account that can be removed from it. */
    OWNER("owner"),

    /** Adds and removes people, sets the policies. */
    ADMIN("admin"),

    /** Uses it. */
    MEMBER("member"),
    ;

    /** True for the roles that may invite, remove and set policy. */
    val administers: Boolean get() = this == OWNER || this == ADMIN

    companion object {
        fun of(wireName: String): WorkspaceRole? = entries.firstOrNull { it.wireName == wireName }
    }
}
