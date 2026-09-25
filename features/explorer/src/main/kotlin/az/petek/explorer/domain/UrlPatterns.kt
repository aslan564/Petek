package az.petek.explorer.domain

import java.net.URI
import java.net.URISyntaxException
import java.text.Normalizer

/** Scheme, host and port of a site: the boundary the explorer never leaves. */
data class SiteOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    /** True when [uri] is an absolute http(s) address on exactly this origin (default ports normalised). */
    fun contains(uri: URI): Boolean {
        if (!uri.isAbsolute || uri.host == null) return false
        return runCatching { of(uri) }.getOrNull() == this
    }

    override fun toString(): String = "$scheme://$host" + if (port == defaultPort(scheme)) "" else ":$port"

    companion object {
        fun of(uri: URI): SiteOrigin {
            val scheme = requireNotNull(uri.scheme?.lowercase()) { "URL without scheme: $uri" }
            val host = requireNotNull(uri.host?.lowercase()) { "URL without host: $uri" }
            return SiteOrigin(scheme, host, if (uri.port == -1) defaultPort(scheme) else uri.port)
        }

        private fun defaultPort(scheme: String): Int =
            when (scheme) {
                "https" -> 443
                "http" -> 80
                else -> -1
            }
    }
}

/**
 * The key site model versions are counted under: origin plus path without a trailing slash, so
 * `https://Site.az/` and `https://site.az` are the same target while `https://site.az/app` is another.
 */
object TargetKey {
    fun of(target: URI): String {
        val path = (target.rawPath ?: "").trimEnd('/')
        return SiteOrigin.of(target).toString() + path
    }
}

/**
 * Generalises page addresses so one kind of page is one entry of the model: `/tickets/t17` and `/tickets/t18` are both
 * `/tickets/{id}`. A path segment counts as an id when it is a number, a UUID, a long hex string, a short prefixed
 * counter such as `t17`/`a3` (never an API version like `v2`), a date (`2026-09-25`, `2026-09`), a numbered slug
 * (`123-noutbuk-islemir`) or a long random token (letters and digits mixed). Without the dates and numbered slugs a
 * calendar or a list of slugged items would spend the whole page budget on copies of one page.
 * Query strings and fragments are dropped: they select data, not a different kind of page.
 */
object UrlPatterns {
    const val ID = "{id}"

    private val NUMBER = Regex("\\d+")
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val HEX = Regex("[0-9a-fA-F]{16,}")
    private val PREFIXED_COUNTER = Regex("[A-Za-z]{1,4}[-_]?\\d+")
    private val API_VERSION = Regex("[vV]\\d+")
    private val TOKEN = Regex("[A-Za-z0-9_-]{20,}")
    private val PREFIXED_ID = Regex("[A-Za-z]{2,6}_[0-9A-Za-z]{8,}")
    private val DATE = Regex("\\d{4}-\\d{2}(-\\d{2})?")
    private val NUMBERED_SLUG = Regex("\\d+-[\\p{L}\\p{N}%-]*[\\p{L}%][\\p{L}\\p{N}%-]*")

    fun isIdSegment(segment: String): Boolean =
        when {
            segment.isEmpty() -> false
            NUMBER.matches(segment) || UUID.matches(segment) || HEX.matches(segment) -> true
            DATE.matches(segment) || NUMBERED_SLUG.matches(segment) -> true
            API_VERSION.matches(segment) -> false
            PREFIXED_COUNTER.matches(segment) || PREFIXED_ID.matches(segment) -> true
            TOKEN.matches(segment) -> segment.any(Char::isDigit) && segment.any(Char::isLetter)
            else -> false
        }

    /** The pattern of [url]'s path, e.g. `/tickets/{id}`; the root is `/`. */
    fun of(url: URI): String = ofPath(url.rawPath ?: "")

    /** The pattern of a path or an absolute address. */
    fun of(pathOrUrl: String): String {
        val uri =
            try {
                URI(pathOrUrl.trim())
            } catch (_: URISyntaxException) {
                return ofPath(pathOrUrl.substringBefore('#').substringBefore('?'))
            }
        return of(uri)
    }

    private fun ofPath(rawPath: String): String {
        val segments = rawPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "/"
        return segments.joinToString("/", prefix = "/") { if (isIdSegment(it)) ID else it }
    }

    /** True when the pattern contains a generalised id segment. */
    fun hasId(pattern: String): Boolean = pattern.split('/').any { it == ID }

    /** The pattern up to (without) its first id segment: `/tickets/{id}/edit` -> `/tickets`; `/` when nothing is left. */
    fun beforeFirstId(pattern: String): String {
        val kept = pattern.split('/').filter { it.isNotEmpty() }.takeWhile { it != ID }
        return if (kept.isEmpty()) "/" else kept.joinToString("/", prefix = "/")
    }

    /** First static segment, e.g. `tickets` for `/tickets/{id}`; null for `/`. */
    fun resource(pattern: String): String? = pattern.split('/').firstOrNull { it.isNotEmpty() && it != ID }

    /** Stable, readable page id derived from a pattern: `/` -> `home`, `/tickets/{id}` -> `tickets-id`. */
    fun pageId(pattern: String): String = Slugs.of(pattern.replace(ID, "id")).ifEmpty { "home" }

    /** [url] without query, fragment and user info, for events and findings (those parts may carry tokens). */
    fun display(url: URI): String {
        val origin = runCatching { SiteOrigin.of(url).toString() }.getOrNull() ?: return url.rawPath ?: ""
        return origin + (url.rawPath?.ifEmpty { "/" } ?: "/")
    }

    /**
     * Resolves a link's `href` against the page address. Returns null for anything that is not an http(s) address
     * (`mailto:`, `javascript:`, fragments of the same page) or cannot be parsed. The fragment and any user info
     * (`user:password@`) are removed, dot segments are resolved and an empty path becomes `/`.
     */
    fun resolve(
        base: URI,
        href: String,
    ): URI? {
        val trimmed = href.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val candidate =
            try {
                base.resolve(URI(trimmed.replace(" ", "%20")))
            } catch (_: URISyntaxException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        val scheme = candidate.scheme?.lowercase()
        val host = candidate.host
        if ((scheme != "http" && scheme != "https") || host == null) return null
        val port = if (candidate.port == -1) "" else ":${candidate.port}"
        val path = candidate.rawPath?.ifEmpty { "/" } ?: "/"
        val query = candidate.rawQuery?.let { "?$it" } ?: ""
        return try {
            URI("$scheme://$host$port$path$query").normalize()
        } catch (_: URISyntaxException) {
            null
        }
    }
}

/** ASCII slugs for ids derived from site text: Azerbaijani letters are transliterated, everything else dropped. */
object Slugs {
    private val TRANSLITERATION =
        mapOf('ə' to "e", 'ı' to "i", 'ö' to "o", 'ü' to "u", 'ç' to "c", 'ş' to "s", 'ğ' to "g", 'ß' to "ss")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val MARKS = Regex("\\p{M}+")

    fun of(
        text: String,
        maxLength: Int = 60,
    ): String {
        val lower = text.lowercase().map { TRANSLITERATION[it] ?: it.toString() }.joinToString("")
        val ascii = MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        return NON_ALNUM
            .replace(ascii, "-")
            .trim('-')
            .take(maxLength)
            .trim('-')
    }

    /** [base] when [isFree] accepts it, else the first of `base-2`, `base-3`, … (joined by [separator]) that it accepts. */
    fun firstFree(
        base: String,
        separator: String = "-",
        isFree: (String) -> Boolean,
    ): String = (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base$separator$it" }).first(isFree)

    /** Like [of] but with `_` separators and a leading letter, as event names and placeholders need. */
    fun identifier(
        text: String,
        fallback: String,
    ): String {
        val slug = of(text).replace('-', '_')
        return when {
            slug.isEmpty() -> fallback
            slug.first().isLetter() -> slug
            else -> "${fallback}_$slug"
        }
    }
}
