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

import az.petek.browser.domain.DialogEvent
import az.petek.browser.domain.DialogType
import az.petek.core.time.HarnessTimestamp

/**
 * The dialogs one session accepted and has not reported yet. Written by the dialog handler, read by
 * [PlaywrightBrowserSession.drainDialogs]; both run on the session thread, the lock only keeps the class safe to use
 * from anywhere. A page that opens dialogs in a loop cannot exhaust memory: only the latest [capacity] are kept.
 *
 * Messages are cut to [maxMessageChars] only when they are drained, *after* the caller's redaction: cutting first
 * could split a secret at the cut and leave its first characters unmasked. Until then a message keeps up to
 * [RAW_FACTOR] times that length, so a secret (far shorter than [maxMessageChars]) that crosses the stored length
 * lies wholly beyond the reported part.
 */
internal class DialogRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS,
) {
    init {
        require(capacity >= 1) { "capacity must be at least 1, was $capacity" }
        require(maxMessageChars >= 2) { "maxMessageChars must be at least 2, was $maxMessageChars" }
    }

    private val lock = Any()
    private val pending = ArrayDeque<DialogEvent>()

    /** [type] as Playwright reports it (`alert`, `confirm`, `prompt`, `beforeunload`); anything else counts as an alert. */
    fun record(
        type: String,
        message: String,
        at: HarnessTimestamp,
    ) {
        val event = DialogEvent(typeOf(type), message.take(maxMessageChars * RAW_FACTOR), at)
        synchronized(lock) {
            pending.addLast(event)
            while (pending.size > capacity) pending.removeFirst()
        }
    }

    /** Everything recorded since the last call, oldest first; each message passes [redact] and is then cut to length. */
    fun drain(redact: (String) -> String = { it }): List<DialogEvent> {
        val drained =
            synchronized(lock) {
                val all = pending.toList()
                pending.clear()
                all
            }
        return drained.map { it.copy(message = clip(redact(it.message))) }
    }

    private fun clip(message: String): String = if (message.length <= maxMessageChars) message else message.take(maxMessageChars - 1) + "…"

    private fun typeOf(type: String): DialogType = DialogType.entries.firstOrNull { it.key == type.lowercase() } ?: DialogType.ALERT

    private companion object {
        const val DEFAULT_CAPACITY = 50
        const val DEFAULT_MAX_MESSAGE_CHARS = 1000

        /** How much longer than the reported length a stored message may be. */
        const val RAW_FACTOR = 2
    }
}
