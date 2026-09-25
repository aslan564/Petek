package az.petek.agent.application

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.DialogEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * The browser accepts JavaScript dialogs itself (a `confirm("Delete?")` gets OK) so a page never hangs on one. What
 * the page asked still matters, to the model deciding the next action and to whoever reads the evidence, so the
 * agent loop and the run functions drain the dialogs after each action and add this note to what they record.
 *
 * Returns null when the page opened no dialog. Reading dialogs is secondary evidence: a failure to read them is
 * logged and never fails the action they belong to.
 */
internal suspend fun BrowserSession.dialogNote(): String? {
    val dialogs =
        try {
            drainDialogs()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug { "$label: could not read browser dialogs: ${e.message}" }
            return null
        }
    return describeDialogs(dialogs)
}

/** `Browser dialogs (accepted): confirm "Bileti silək?"; alert "Yadda saxlandı".`, or null for none. */
internal fun describeDialogs(dialogs: List<DialogEvent>): String? {
    if (dialogs.isEmpty()) return null
    return "Browser dialogs (accepted): " + dialogs.joinToString("; ") { "${it.type.key} ${quote(it.message)}" } + "."
}

/** [text] followed by [note] when there is one. */
internal fun withNote(
    text: String,
    note: String?,
): String = if (note == null) text else "$text $note"
