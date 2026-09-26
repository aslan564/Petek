/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.mail.domain

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Heuristic extraction tuned for verification and invitation mails in Azerbaijani and English.
 *
 * **Code**: a standalone 4–8 digit number. Never a code: numbers glued to words (`PTK-4821`, `user1234`), decimals,
 * digits inside URLs or e-mail addresses, obvious years (4-digit 19xx/20xx) and phone numbers (`+994 50 1234567`,
 * `012 437 12 34`). Among the remaining candidates the winner is, in order: on the same line as one of
 * `kod|code|OTP|şifrə|təsdiq` (case-insensitive), else within [NEAR_CHARS] of one; then a 6-digit one; then the one
 * closest to a keyword (codes usually follow it); then the first. The subject is scanned with the body; the text part
 * is preferred and the HTML part only used when the text yields nothing.
 *
 * **Link**: the first http(s) URL whose (percent-decoded) text matches `/invite/|verify|confirm|activate|dəvət`
 * (case-insensitive): `<a href>`s of the HTML part in document order first, then URLs written in the text.
 *
 * The purpose decides whether the result counts: CODE needs a code, LINK a link, ANY either; everything found is
 * returned. Null when the purpose is not satisfied.
 */
class DefaultVerificationExtractor : VerificationExtractor {
    override fun extract(
        message: MailMessage,
        purpose: MailPurpose,
    ): VerificationCode? {
        val code = findCode(message)
        val link = findLink(message)
        val satisfied =
            when (purpose) {
                MailPurpose.CODE -> code != null
                MailPurpose.LINK -> link != null
                MailPurpose.ANY -> code != null || link != null
            }
        return if (satisfied) VerificationCode(code = code, link = link, messageId = message.id) else null
    }

    /**
     * Like the link rule above, but a link counts when its text (or its percent-decoded text) contains a match of
     * [pattern] instead of one of the built-in hints, so a site's own link shape (`set-password?token=`) is found.
     */
    override fun extractLink(
        message: MailMessage,
        pattern: Regex,
    ): VerificationCode? {
        val link = findLink(message, pattern) ?: return null
        return VerificationCode(code = findCode(message), link = link, messageId = message.id)
    }

    private fun findCode(message: MailMessage): String? {
        val html = message.html?.takeIf { it.isNotBlank() }
        return bestCode(message.subject + "\n" + message.text)
            ?: html?.let { bestCode(message.subject + "\n" + HtmlText.toPlainText(it)) }
    }

    private fun bestCode(text: String): String? {
        val scan = maskAddresses(text)
        val keywords = KEYWORD.findAll(scan).map { it.range }.toList()
        val phones =
            PHONE_RUN
                .findAll(scan)
                .filter { isPhoneLike(it.value) }
                .map { it.range }
                .toList()
        return DIGITS
            .findAll(scan)
            .filter { token -> isStandalone(scan, token.range) && !isYear(token.value) && phones.none { token.range.first in it } }
            .map { token -> candidate(token, keywords, scan) }
            .minWithOrNull(CANDIDATE_ORDER)
            ?.value
    }

    /** URLs and e-mail addresses are blanked out (same length, so positions stay valid): their digits are never codes. */
    private fun maskAddresses(text: String): String =
        listOf(ANY_URL, EMAIL).fold(text) { masked, pattern -> masked.replace(pattern) { " ".repeat(it.value.length) } }

    private fun isStandalone(
        text: String,
        token: IntRange,
    ): Boolean {
        val before = text.getOrNull(token.first - 1)
        val beforeThat = text.getOrNull(token.first - 2)
        val after = text.getOrNull(token.last + 1)
        val afterThat = text.getOrNull(token.last + 2)
        return when {
            before != null && (before.isLetterOrDigit() || before == '_' || before == '+') -> false
            before in JOINERS && beforeThat?.isLetterOrDigit() == true -> false
            before in DECIMAL_MARKS && beforeThat?.isDigit() == true -> false
            after != null && (after.isLetterOrDigit() || after == '_') -> false
            after in DECIMAL_MARKS && afterThat?.isDigit() == true -> false
            else -> true
        }
    }

    private fun isYear(token: String): Boolean = token.length == 4 && (token.startsWith("19") || token.startsWith("20"))

    /**
     * International numbers start with `+`; local ones come in 3+ short groups (`050 123 45 67`) or with a
     * parenthesised area code (`(012) 4371234`). Nine or more digits in total, so `482913 2026` is not a phone.
     */
    private fun isPhoneLike(run: String): Boolean {
        val digits = run.count(Char::isDigit)
        val groups = DIGIT_GROUP.findAll(run).map { it.value.length }.toList()
        return when {
            run.startsWith("+") -> digits in 7..MAX_PHONE_DIGITS
            run.startsWith("(") -> digits in 9..MAX_PHONE_DIGITS && groups.size >= 2
            else -> digits in 9..MAX_PHONE_DIGITS && groups.size >= 3 && groups.max() <= 4
        }
    }

    private fun candidate(
        token: MatchResult,
        keywords: List<IntRange>,
        text: String,
    ): CodeCandidate {
        var sameLine = false
        var nearest = Int.MAX_VALUE
        var weighted = Int.MAX_VALUE
        for (keyword in keywords) {
            val tokenFollows = keyword.last < token.range.first
            val between =
                if (tokenFollows) {
                    text.substring(keyword.last + 1, token.range.first)
                } else {
                    text.substring(token.range.last + 1, keyword.first)
                }
            val gap = between.length
            sameLine = sameLine || '\n' !in between
            nearest = minOf(nearest, gap)
            weighted = minOf(weighted, if (tokenFollows) gap else gap * 2)
        }
        val tier =
            when {
                sameLine -> 0
                nearest <= NEAR_CHARS -> 1
                else -> 2
            }
        return CodeCandidate(token.value, token.range.first, tier, weighted)
    }

    private fun findLink(
        message: MailMessage,
        hint: Regex = LINK_HINT,
    ): URI? {
        val html = message.html?.takeIf { it.isNotBlank() }
        val hrefs = html?.let { HtmlText.anchorHrefs(it) }.orEmpty().asSequence()
        val textUrls = urlsIn(message.text)
        val htmlTextUrls = html?.let { urlsIn(HtmlText.toPlainText(it)) } ?: emptySequence()
        return (hrefs + textUrls + htmlTextUrls)
            .filter { hint.containsMatchIn(it) || hint.containsMatchIn(percentDecoded(it)) }
            .mapNotNull(::toHttpUri)
            .firstOrNull()
    }

    private fun urlsIn(text: String): Sequence<String> = HTTP_URL.findAll(text).map { trimTrailingPunctuation(it.value) }

    /** `…/invite/abc.` or `(see https://…/confirm)`: sentence punctuation and unbalanced closers are not the URL. */
    private fun trimTrailingPunctuation(url: String): String {
        var end = url.length
        while (end > 0 && isTrailingNoise(url, end)) end--
        return url.substring(0, end)
    }

    private fun isTrailingNoise(
        url: String,
        end: Int,
    ): Boolean {
        val last = url[end - 1]
        val head = url.substring(0, end)
        return when (last) {
            in TRAILING_PUNCTUATION -> true
            ')' -> head.count { it == ')' } > head.count { it == '(' }
            ']' -> head.count { it == ']' } > head.count { it == '[' }
            else -> false
        }
    }

    private fun percentDecoded(url: String): String =
        try {
            URLDecoder.decode(url, StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            url
        }

    private fun toHttpUri(raw: String): URI? {
        val uri =
            try {
                URI(escapeIllegalCharacters(raw))
            } catch (_: URISyntaxException) {
                return null
            }
        return uri.takeIf { it.scheme?.lowercase() in HTTP_SCHEMES && !it.host.isNullOrEmpty() }
    }

    /** Percent-encodes what [URI] rejects (non-ASCII such as `dəvət`, spaces, stray `%`) and leaves valid escapes alone. */
    private fun escapeIllegalCharacters(raw: String): String =
        buildString {
            var i = 0
            while (i < raw.length) {
                val codePoint = raw.codePointAt(i)
                when {
                    codePoint == '%'.code && !isPercentEscape(raw, i) -> append("%25")
                    codePoint in 0x21..0x7E && codePoint.toChar() !in ILLEGAL_IN_URI -> appendCodePoint(codePoint)
                    else -> Character.toString(codePoint).toByteArray(StandardCharsets.UTF_8).forEach { appendEscaped(it) }
                }
                i += Character.charCount(codePoint)
            }
        }

    private fun StringBuilder.appendEscaped(byte: Byte) {
        val value = byte.toInt() and 0xFF
        append('%').append(HEX[value shr 4]).append(HEX[value and 0xF])
    }

    private fun isPercentEscape(
        text: String,
        index: Int,
    ): Boolean = index + 2 < text.length && text[index + 1].isHexDigit() && text[index + 2].isHexDigit()

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private class CodeCandidate(
        val value: String,
        val start: Int,
        val tier: Int,
        val weightedDistance: Int,
    )

    private companion object {
        /** A code this close to a keyword on another line (`Kodunuz:` / blank line / `482913`) still counts as near. */
        const val NEAR_CHARS = 40
        const val PREFERRED_LENGTH = 6
        const val MAX_PHONE_DIGITS = 15
        const val HEX = "0123456789ABCDEF"

        val DIGITS = Regex("""(?<!\d)\d{4,8}(?!\d)""")
        val DIGIT_GROUP = Regex("""\d+""")
        val KEYWORD = Regex("kod|code|otp|şifrə|təsdiq", RegexOption.IGNORE_CASE)
        val PHONE_RUN = Regex("""(?<![\p{L}\p{N}_])(\+\s?)?\(?\d+\)?(?:[ .\-]\(?\d+\)?)*""")
        val ANY_URL = Regex("""(?:https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)
        val HTTP_URL = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

        /** Starts only where a run of address characters starts and never backtracks, so long runs stay linear. */
        val EMAIL = Regex("""(?<![\p{L}\p{N}._%+\-])[\p{L}\p{N}._%+\-]++@[\p{L}\p{N}\-]++(?:\.[\p{L}\p{N}\-]++)+""")
        val LINK_HINT = Regex("/invite/|verify|confirm|activate|dəvət", RegexOption.IGNORE_CASE)

        val JOINERS = setOf('-', '_', '/', '#')
        val DECIMAL_MARKS = setOf('.', ',')
        val TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', '*', '»', '›', '\'', '"')
        val ILLEGAL_IN_URI = setOf('"', '<', '>', '\\', '^', '`', '{', '|', '}', '[', ']')
        val HTTP_SCHEMES = setOf("http", "https")

        val CANDIDATE_ORDER =
            compareBy<CodeCandidate>(
                { it.tier },
                { if (it.value.length == PREFERRED_LENGTH) 0 else 1 },
                { it.weightedDistance },
                { it.start },
            )
    }
}
