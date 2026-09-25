package az.petek.browser.infrastructure

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Options for Playwright's `BrowserType.launchServer`, serialized as the JSON object [BundledScripts.browserServer]
 * expects. The server always listens on 127.0.0.1 with a random port: a browser server accepts any client that knows
 * its URL, so it must never be reachable from other machines.
 */
internal data class BrowserServerOptions(
    val headless: Boolean,
    val launchTimeout: Duration = 60.seconds,
    /** Overrides the Chromium binary; null uses the one Playwright installed. */
    val executablePath: Path? = null,
) {
    fun toJson(): String =
        buildString {
            append("{\"headless\":").append(headless)
            append(",\"host\":\"127.0.0.1\",\"port\":0")
            append(",\"timeout\":").append(launchTimeout.inWholeMilliseconds)
            if (executablePath != null) append(",\"executablePath\":").append(jsonString(executablePath.toString()))
            append('}')
        }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char < ' ' -> append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            append('"')
        }
}
