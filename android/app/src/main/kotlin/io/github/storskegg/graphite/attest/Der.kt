package io.github.storskegg.graphite.attest

import java.math.BigInteger

/**
 * A minimal DER reader: enough for the key description in an attestation certificate. Each
 * element is its tag (class, constructed, number) and its contents; anything malformed throws.
 */
class Der private constructor(private val b: ByteArray, val tagClass: Int, val constructed: Boolean, val tag: Int,
                              private val start: Int, private val end: Int) {
    val contents: ByteArray get() = b.copyOfRange(start, end)

    /** The elements inside a constructed one, in order. */
    fun children(): List<Der> {
        check(constructed) { "not a constructed element" }
        val out = ArrayList<Der>()
        var at = start
        while (at < end) out += read(b, at, end).also { at = it.end }
        return out
    }

    /** For an explicitly tagged element, the one element inside. */
    fun inner(): Der = children().single()

    fun integer(): BigInteger { check(isUniversal(INTEGER) || isUniversal(ENUMERATED)) { "not an integer" }; return BigInteger(contents) }
    fun int(): Int = integer().intValueExact()
    fun octets(): ByteArray { check(isUniversal(OCTET_STRING)) { "not an octet string" }; return contents }
    fun bool(): Boolean { check(isUniversal(BOOLEAN) && end - start == 1) { "not a boolean" }; return b[start] != 0.toByte() }

    private fun isUniversal(t: Int) = tagClass == UNIVERSAL && tag == t

    /** The element with context tag [n] among a SEQUENCE's children, if there is one. */
    fun context(n: Int): Der? = children().firstOrNull { it.tagClass == CONTEXT && it.tag == n }

    companion object {
        const val UNIVERSAL = 0
        const val CONTEXT = 2
        const val BOOLEAN = 1
        const val INTEGER = 2
        const val OCTET_STRING = 4
        const val ENUMERATED = 10
        const val SEQUENCE = 16

        /** The one element that makes up [b]. */
        fun parse(b: ByteArray): Der = read(b, 0, b.size).also { check(it.end == b.size) { "bytes after the element" } }

        private fun read(b: ByteArray, from: Int, limit: Int): Der {
            var at = from
            fun next(): Int { check(at < limit) { "truncated" }; return b[at++].toInt() and 0xff }
            val first = next()
            var tag = first and 0x1f
            if (tag == 0x1f) {
                tag = 0
                do {
                    val x = next()
                    check(tag < (1 shl 23)) { "tag too large" }
                    tag = (tag shl 7) or (x and 0x7f)
                } while (x and 0x80 != 0)
            }
            var len = next()
            if (len and 0x80 != 0) {
                val n = len and 0x7f
                check(n in 1..4) { "bad length" }
                len = 0
                repeat(n) { len = (len shl 8) or next() }
                check(len >= 0) { "bad length" }
            }
            check(len <= limit - at) { "truncated" }
            return Der(b, first shr 6, first and 0x20 != 0, tag, at, at + len)
        }
    }
}
