package az.petek.explorer.domain

import java.net.URI

enum class SkipReason {
    /** Not an http(s) page address. */
    NOT_WEB,

    /** Another scheme, host or port than the target: the explorer never leaves the target's origin. */
    OTHER_ORIGIN,

    /** Looks like it ends a session or destroys data (logout, delete, unsubscribe, …). */
    UNSAFE,

    /** Disallowed by the site's `robots.txt`. */
    ROBOTS,

    /** A machine endpoint (`/api/…`, `/graphql`, the `/test/…` oracle API), not a page. */
    NOT_A_PAGE,

    /** A file download (PDF, archive, image, …). */
    DOWNLOAD,
}

sealed interface LinkVerdict {
    data object Follow : LinkVerdict

    data class Skip(
        val reason: SkipReason,
    ) : LinkVerdict
}

/**
 * Decides which links the explorer may request or open. Only same-origin page addresses are followed; anything that
 * looks like logging out, deleting or unsubscribing is never followed, judged by the path *and* the link text
 * (in English and Azerbaijani), because a GET link can have side effects on a badly built site.
 */
class LinkPolicy(
    private val origin: SiteOrigin,
    private val robots: RobotsRules = RobotsRules.NONE,
) {
    fun verdict(
        url: URI,
        text: String = "",
    ): LinkVerdict {
        val scheme = url.scheme?.lowercase()
        return when {
            scheme != "http" && scheme != "https" -> LinkVerdict.Skip(SkipReason.NOT_WEB)
            !origin.contains(url) -> LinkVerdict.Skip(SkipReason.OTHER_ORIGIN)
            looksUnsafe(url.rawPath.orEmpty() + " " + url.rawQuery.orEmpty(), text) -> LinkVerdict.Skip(SkipReason.UNSAFE)
            isMachineEndpoint(url.rawPath.orEmpty()) -> LinkVerdict.Skip(SkipReason.NOT_A_PAGE)
            isDownload(url.rawPath.orEmpty()) -> LinkVerdict.Skip(SkipReason.DOWNLOAD)
            !robots.allows(url.rawPath.orEmpty() + (url.rawQuery?.let { "?$it" } ?: "")) -> LinkVerdict.Skip(SkipReason.ROBOTS)
            else -> LinkVerdict.Follow
        }
    }

    companion object {
        /**
         * Words that mark a destructive or session-ending link. Stems of five or more letters also match longer words
         * (`deleted`, `logoutAll`); shorter ones must be whole words (`sil`, `drop`).
         */
        val UNSAFE_STEMS: Set<String> =
            setOf(
                "logout",
                "logoff",
                "signout",
                "delete",
                "remove",
                "destroy",
                "erase",
                "purge",
                "drop",
                "unsubscribe",
                "deactivate",
                "revoke",
                "terminate",
                "wipe",
                "truncate",
                "çıxış",
                "cixis",
                "sil",
                "silin",
                "silmək",
                "silmek",
                "ləğv",
                "legv",
            )

        /** Two-word forms such as `log out`, `sign-out`, `log_off` (the words are split at any separator). */
        private val UNSAFE_PAIRS = setOf("log out", "log off", "sign out", "sign off")

        private val MACHINE_PREFIXES = listOf("/api/", "/test/", "/graphql")

        private val DOWNLOAD_EXTENSIONS =
            setOf(
                "pdf",
                "zip",
                "gz",
                "tgz",
                "rar",
                "7z",
                "exe",
                "dmg",
                "msi",
                "apk",
                "iso",
                "csv",
                "xls",
                "xlsx",
                "doc",
                "docx",
                "ppt",
                "pptx",
                "png",
                "jpg",
                "jpeg",
                "gif",
                "webp",
                "svg",
                "ico",
                "mp3",
                "mp4",
                "webm",
                "avi",
                "mov",
                "css",
                "js",
                "json",
                "xml",
                "txt",
                "woff",
                "woff2",
                "ttf",
            )

        /** True when the address or the visible text of a link suggests logging out or destroying data. */
        fun looksUnsafe(vararg texts: String): Boolean =
            texts.any { text ->
                val words = Keywords.words(text)
                Keywords.containsStem(text, UNSAFE_STEMS) || words.zipWithNext { a, b -> "$a $b" }.any { it in UNSAFE_PAIRS }
            }

        private fun isMachineEndpoint(path: String): Boolean {
            val lower = path.lowercase()
            return MACHINE_PREFIXES.any { prefix -> lower == prefix.trimEnd('/') || lower.startsWith(prefix.trimEnd('/') + "/") }
        }

        private fun isDownload(path: String): Boolean {
            val last = path.substringAfterLast('/')
            if (!last.contains('.')) return false
            return last.substringAfterLast('.').lowercase() in DOWNLOAD_EXTENSIONS
        }
    }
}
