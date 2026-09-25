package az.petek.mail.domain

/**
 * Just enough HTML handling to read e-mails: visible text with line breaks where blocks end, and anchor targets.
 * Not a parser; e-mail HTML is simple and a heuristic miss only costs a candidate, never a crash.
 */
internal object HtmlText {
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val invisible = Regex("""<(script|style|head|title)\b[^>]*>.*?</\1\s*>""", options)
    private val lineBreak =
        Regex(
            """<br\b[^>]*>|</?(p|div|tr|li|ul|ol|table|thead|tbody|h[1-6]|blockquote|section|article|header|footer|center)\b[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
    private val tag = Regex("<[^>]*>")
    private val whitespace = Regex("""\s+""")
    private val anchorHref = Regex("""<a\b[^>]*?\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val namedEntities =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "ndash" to "–",
            "mdash" to "—",
            "laquo" to "«",
            "raquo" to "»",
            "copy" to "©",
        )

    /** Visible text: HTML whitespace collapsed, block ends and `<br>` as new lines, other tags as spaces. */
    fun toPlainText(html: String): String {
        val visible = html.replace(comment, " ").replace(invisible, " ").replace(whitespace, " ")
        return decodeEntities(visible.replace(lineBreak, "\n").replace(tag, " "))
    }

    /** `href` values of `<a>` elements in document order, entities decoded. */
    fun anchorHrefs(html: String): List<String> =
        anchorHref
            .findAll(html.replace(comment, " "))
            .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }
            .map { decodeEntities(it).trim() }
            .toList()

    fun decodeEntities(text: String): String =
        entity.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x", ignoreCase = true) -> codePoint(name.substring(2).toIntOrNull(16))
                name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull())
                else -> namedEntities[name.lowercase()]
            } ?: match.value
        }

    private fun codePoint(value: Int?): String? = value?.takeIf { Character.isValidCodePoint(it) && it != 0 }?.let(Character::toString)
}
