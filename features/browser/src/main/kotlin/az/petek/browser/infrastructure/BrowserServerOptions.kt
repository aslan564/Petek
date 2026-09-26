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
