package az.petek.app.cli

import az.petek.app.config.WebUrls
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import java.net.URI
import java.net.URISyntaxException

/**
 * `--url <url>`: an absolute http(s) URL without credentials, checked while parsing the command line and returned in
 * its canonical spelling ([WebUrls.canonical]) for the target policy.
 */
internal fun ParameterHolder.urlOption(help: String) =
    option("--url", help = help, metavar = "URL").convert { raw ->
        val url =
            try {
                URI(raw.trim())
            } catch (_: URISyntaxException) {
                fail("'$raw' is not a valid URL")
            }
        when {
            url.scheme?.lowercase() !in setOf("http", "https") || url.host.isNullOrBlank() -> fail("'$raw' is not an absolute http(s) URL")
            url.rawUserInfo != null -> fail("the URL must not contain credentials")
            else -> WebUrls.canonical(url)
        }
    }
