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

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserContextLostException
import com.microsoft.playwright.PlaywrightException
import java.io.IOException

/**
 * Runs one browser [action] and turns Playwright and I/O failures into a [BrowserActionException] whose message is
 * a short, single-line reason the agent (and the report) can use. [typedText] is text the action types into the
 * page: it may be a password, so it is masked in the message, and the original exception is not attached as the
 * cause when its own message contains it.
 */
internal inline fun <T> translatingFailures(
    action: String,
    typedText: String? = null,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: PlaywrightException) {
        throw PlaywrightFailures.describe(action, e, typedText)
    } catch (e: IOException) {
        throw PlaywrightFailures.describe(action, e, typedText)
    }

internal object PlaywrightFailures {
    private const val MAX_REASON_CHARS = 400

    fun describe(
        action: String,
        failure: Exception,
        typedText: String?,
    ): BrowserActionException {
        val raw = failure.message.orEmpty()
        val secret = typedText?.takeIf { it.isNotEmpty() }
        val reason = reasonOf(raw).let { if (secret == null) it else it.replace(secret, "***") }
        val cause = if (secret != null && raw.contains(secret)) null else failure
        if (LOST_CONTEXT.any { raw.contains(it, ignoreCase = true) }) return BrowserContextLostException("$action failed: $reason", cause)
        return BrowserActionException("$action failed: $reason", cause)
    }

    /** What Playwright says when the page, context or browser behind a session is gone for good. */
    private val LOST_CONTEXT =
        listOf("Target page, context or browser has been closed", "Target crashed", "Page crashed", "Browser has been closed")

    /**
     * Playwright Java renders driver errors as `Error { message='…' name='…' stack='…' }` followed by a `Call log:`
     * section. The reason is the message plus the call log (what Playwright was waiting for), on one line, without
     * the Node.js stack trace.
     */
    fun reasonOf(raw: String): String {
        val message =
            if (raw.contains("message='")) {
                raw.substringAfter("message='").substringBefore("\n  name='")
            } else {
                raw.substringBefore("\nCall log:")
            }
        val callLog =
            raw
                .substringAfter("\nCall log:", missingDelimiterValue = "")
                .lineSequence()
                .map { it.trim().trimStart('-', ' ') }
                .filter { it.isNotEmpty() }
                .toList()
        val reason =
            buildString {
                append(oneLine(message))
                if (callLog.isNotEmpty()) append(" (").append(callLog.joinToString("; ")).append(')')
            }
        return when {
            reason.isBlank() -> "unknown browser error"
            reason.length > MAX_REASON_CHARS -> reason.take(MAX_REASON_CHARS - 1) + "…"
            else -> reason
        }
    }

    private fun oneLine(text: String): String =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
