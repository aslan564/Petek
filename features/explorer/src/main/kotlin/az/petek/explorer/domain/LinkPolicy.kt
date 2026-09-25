package az.petek.explorer.domain

import java.net.URI

enum class SkipReason {
    /** Not an http(s) page address. */
    NOT_WEB,

    /** Another scheme, host or port than the target: the explorer never leaves the target's origin. */
    OTHER_ORIGIN,

    /** Looks like it ends a session, destroys data or decides something (logout, delete, unsubscribe, approve, …). */
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
 * looks like logging out, deleting, unsubscribing, approving or rejecting is never followed, judged by the path (as
 * written and percent-decoded, so `/%C3%A7%C4%B1x%C4%B1%C5%9F` is `/çıxış`) *and* the link text (in English and
 * Azerbaijani), because a GET link can have side effects on a badly built site and the crawl must not change anything.
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
            looksUnsafe(url.rawPath.orEmpty() + " " + url.rawQuery.orEmpty(), decoded(url), text) -> LinkVerdict.Skip(SkipReason.UNSAFE)
            isMachineEndpoint(url.rawPath.orEmpty()) -> LinkVerdict.Skip(SkipReason.NOT_A_PAGE)
            isDownload(url.rawPath.orEmpty()) -> LinkVerdict.Skip(SkipReason.DOWNLOAD)
            !robots.allows(url.rawPath.orEmpty() + (url.rawQuery?.let { "?$it" } ?: "")) -> LinkVerdict.Skip(SkipReason.ROBOTS)
            else -> LinkVerdict.Follow
        }
    }

    /** Path and query with percent-escapes decoded; empty when the escapes are not valid UTF-8. */
    private fun decoded(url: URI): String = runCatching { url.path.orEmpty() + " " + url.query.orEmpty() }.getOrDefault("")

    companion object {
        /**
         * Words that mark a destructive, session-ending or deciding link. Stems of five or more letters also match
         * longer words (`deleted`, `logoutAll`); shorter ones must be whole words (`sil`, `drop`). Words are compared
         * [Keywords.fold]ed, so `ÇIXIŞ` and `cixis` are `çıxış`.
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
                // A crawl never decides anything, even when a badly built site does it with a GET link.
                "approve",
                "reject",
                "decline",
            )

        /**
         * Deciding verbs that must match as whole words: `Təsdiqlə` (approve) is unsafe, while `Təsdiqlər` (the list of
         * approvals) is a page worth exploring.
         */
        val UNSAFE_WORDS: Set<String> = setOf("təsdiqlə", "rədd", "imtina")

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
                Keywords.containsStem(text, UNSAFE_STEMS) ||
                    UNSAFE_WORDS.any { Keywords.containsPhrase(text, it) } ||
                    UNSAFE_PAIRS.any { Keywords.containsPhrase(text, it) }
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
