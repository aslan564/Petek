package az.petek.oracle.infrastructure

import java.nio.charset.StandardCharsets

/** Percent-encoding for URLs the oracle builds itself (values) and for paths callers rendered (repair only). */
internal object UrlEncoding {
    private const val HEX = "0123456789ABCDEF"
    private val illegalInUri = setOf('"', '<', '>', '\\', '^', '`', '{', '|', '}')

    /** Encodes everything but RFC 3986 unreserved characters: safe as a path segment or a query value (`+` → `%2B`). */
    fun component(value: String): String =
        buildString {
            value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                val char = (byte.toInt() and 0xFF).toChar()
                if (char.isUnreserved()) append(char) else appendEscaped(byte)
            }
        }

    /**
     * Makes an already rendered path/query addressable without changing its meaning: non-ASCII, spaces and characters
     * illegal in URIs are encoded, a `%` that does not start a valid escape becomes `%25`, everything else is kept.
     */
    fun repair(pathAndQuery: String): String =
        buildString {
            var i = 0
            while (i < pathAndQuery.length) {
                val codePoint = pathAndQuery.codePointAt(i)
                when {
                    codePoint == '%'.code && !isEscapeAt(pathAndQuery, i) -> append("%25")
                    codePoint in 0x21..0x7E && codePoint.toChar() !in illegalInUri -> appendCodePoint(codePoint)
                    else -> Character.toString(codePoint).toByteArray(StandardCharsets.UTF_8).forEach { appendEscaped(it) }
                }
                i += Character.charCount(codePoint)
            }
        }

    private fun Char.isUnreserved(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"

    private fun StringBuilder.appendEscaped(byte: Byte) {
        val value = byte.toInt() and 0xFF
        append('%').append(HEX[value shr 4]).append(HEX[value and 0xF])
    }

    private fun isEscapeAt(
        text: String,
        index: Int,
    ): Boolean = index + 2 < text.length && text[index + 1].isHexDigit() && text[index + 2].isHexDigit()

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
