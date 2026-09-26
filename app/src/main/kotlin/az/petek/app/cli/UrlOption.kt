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
internal fun ParameterHolder.urlOption(
    help: String,
    name: String = "--url",
) = option(name, help = help, metavar = "URL").convert { raw ->
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
