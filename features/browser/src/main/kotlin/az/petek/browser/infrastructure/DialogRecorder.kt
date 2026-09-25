package az.petek.browser.infrastructure

import az.petek.browser.domain.DialogEvent
import az.petek.browser.domain.DialogType
import az.petek.core.time.HarnessTimestamp

/**
 * The dialogs one session accepted and has not reported yet. Written by the dialog handler, read by
 * [PlaywrightBrowserSession.drainDialogs]; both run on the session thread, the lock only keeps the class safe to use
 * from anywhere. A page that opens dialogs in a loop cannot exhaust memory: only the latest [capacity] are kept, each
 * message cut to [maxMessageChars].
 */
internal class DialogRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS,
) {
    private val lock = Any()
    private val pending = ArrayDeque<DialogEvent>()

    /** [type] as Playwright reports it (`alert`, `confirm`, `prompt`, `beforeunload`); anything else counts as an alert. */
    fun record(
        type: String,
        message: String,
        at: HarnessTimestamp,
    ) {
        val event = DialogEvent(typeOf(type), clip(message), at)
        synchronized(lock) {
            pending.addLast(event)
            while (pending.size > capacity) pending.removeFirst()
        }
    }

    /** Everything recorded since the last call, oldest first. */
    fun drain(): List<DialogEvent> =
        synchronized(lock) {
            val drained = pending.toList()
            pending.clear()
            drained
        }

    private fun clip(message: String): String = if (message.length <= maxMessageChars) message else message.take(maxMessageChars - 1) + "…"

    private fun typeOf(type: String): DialogType = DialogType.entries.firstOrNull { it.key == type.lowercase() } ?: DialogType.ALERT

    private companion object {
        const val DEFAULT_CAPACITY = 50
        const val DEFAULT_MAX_MESSAGE_CHARS = 1000
    }
}
