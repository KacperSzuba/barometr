package pl.barometr.identity.internal.user

/**
 * Where an address is, roughly, for somebody deciding whether a session is theirs.
 *
 * **Roughly is the whole contract.** A city from an address database is a guess — mobile
 * networks route through another voivodeship, a VPN is somewhere else entirely — so it
 * is shown beside the address rather than instead of it, and nothing decides anything
 * from it. What it is for is the moment somebody scans their device list and sees
 * "Singapur" where they have only ever been in Warsaw.
 *
 * Null whenever there is no honest answer: no address, an address nobody can place, a
 * private network, or no database configured at all. A wrong guess in this list is worse
 * than a blank.
 */
interface ApproximateLocations {

    /** Something like `Warszawa, PL`, or `PL`, or null. */
    fun locate(clientIp: String?): String?
}
