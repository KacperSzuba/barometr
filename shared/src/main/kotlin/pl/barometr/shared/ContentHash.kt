package pl.barometr.shared

import java.security.MessageDigest

/**
 * SHA-256 of a byte payload, and the system's universal content address.
 *
 * The same value is the object-storage key, the idempotency key for ingestion and
 * the identity of a document version. Deduplication across sources therefore
 * needs no coordination: two connectors that fetch the same PDF independently
 * arrive at the same address.
 *
 * Holds the hex string, not the bytes. A value class wrapping a [ByteArray]
 * inherits the array's identity-based `equals`, which would make two hashes of
 * the same content unequal — silently defeating every deduplication check built
 * on comparing them. Hex is also the dominant representation in practice: storage
 * keys, log lines and URLs all want it, and only the database column wants bytes.
 */
@JvmInline
value class ContentHash private constructor(val hex: String) {

    /** Big-endian bytes, for a `bytea` column. */
    val bytes: ByteArray
        get() = ByteArray(BYTE_LENGTH) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    override fun toString(): String = hex

    companion object {
        const val BYTE_LENGTH = 32

        fun of(payload: ByteArray): ContentHash =
            ofBytes(MessageDigest.getInstance("SHA-256").digest(payload))

        fun ofBytes(bytes: ByteArray): ContentHash {
            require(bytes.size == BYTE_LENGTH) {
                "A SHA-256 hash is $BYTE_LENGTH bytes, got ${bytes.size}"
            }
            return ContentHash(bytes.joinToString("") { byte -> "%02x".format(byte) })
        }

        fun parse(hex: String): ContentHash {
            val normalised = hex.lowercase()
            require(normalised.length == BYTE_LENGTH * 2 && normalised.all { it in HEX_DIGITS }) {
                "Not a SHA-256 hex string: '$hex'"
            }
            return ContentHash(normalised)
        }

        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
