package pl.barometr.identity.api

/**
 * What a user is allowed to do.
 *
 * A closed set rather than free text, in the schema (`ck_user_roles_known`) and here.
 * The two are one fact in two places and change together in one commit — the same
 * arrangement `PayloadKind` and the job statuses use — because a role nobody's code
 * checks for grants nothing, so adding one is never only a data change.
 */
enum class Role {
    /** Everyone who registers. Reads the archive; changes nothing about the system. */
    USER,

    /**
     * Starts replays and reads completeness reports. Cannot be self-assigned:
     * registration grants [USER] and nothing else, and a backfill is thousands of
     * requests to somebody else's server.
     */
    OPERATOR,
    ;

    companion object {
        /**
         * Null for a role this deployment does not know. A token minted before a role
         * was removed should authorise less, never fail to parse.
         */
        fun ofName(name: String): Role? = entries.firstOrNull { it.name == name }
    }
}
