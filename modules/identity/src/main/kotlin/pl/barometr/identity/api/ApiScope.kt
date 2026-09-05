package pl.barometr.identity.api

/**
 * What a key may reach.
 *
 * Two, because there are two kinds of cost. A read is one act or one list; a bulk
 * download is the dataset, which is where a public API stops being cheap to serve. A key
 * that only ever needed the first should not be able to do the second by accident.
 */
enum class ApiScope(val wireName: String) {
    READ("read"),
    BULK("bulk"),
    ;

    companion object {
        fun of(wireName: String): ApiScope? = entries.firstOrNull { it.wireName == wireName }
    }
}
