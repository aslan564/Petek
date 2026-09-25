package az.petek.explorer.application

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.core.time.HarnessClock
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ActionModel
import az.petek.explorer.domain.FieldModel
import az.petek.explorer.domain.FormModel
import az.petek.explorer.domain.Keywords
import az.petek.explorer.domain.LinkPolicy
import az.petek.explorer.domain.PageModel
import az.petek.explorer.domain.Provenance
import az.petek.explorer.domain.TrialOutcome
import az.petek.explorer.domain.TrialTouch
import az.petek.explorer.domain.UrlPatterns
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Phase TRIAL_TOUCH: submits each observed CREATE form exactly once with harmless data made from its field types,
 * then stops. Only the explorer's use case starts it, and only with `allowWrites` on a target the [az.petek.explorer
 * .domain.TestTargetCheck] confirmed as test data. Rules that keep it harmless:
 *
 * - only forms classified CREATE, never login, sign-up, verification, approval or deletion forms, never a form with a
 *   password or file field, never a form whose address or button looks destructive;
 * - one submission per form, from a role that was offered it, on a page of the target's own origin;
 * - every text carries a unique marker (`Pətək sınaq …`) so the result can be recognised, and nothing is deleted
 *   afterwards (a test target is torn down as a whole).
 *
 * After submitting it looks at what happened: the marker on the submitter's page (accepted), an error-like message
 * (rejected), and whether the other roles' open pages showed the marker live within [ExplorerSettings.liveEffectTimeout].
 */
internal class TrialToucher(
    private val context: ExplorationContext,
    private val sessions: Map<String, BrowserSession>,
    private val clock: HarnessClock,
) {
    private var touched = 0

    suspend fun run() {
        val accumulator = context.accumulator
        for (action in accumulator.actions().filter { it.kind == ActionKind.CREATE }) {
            currentCoroutineContext().ensureActive()
            if (context.deadlinePassed()) return
            val page = accumulator.pages().firstOrNull { it.id == action.pageId } ?: continue
            val form = page.forms.firstOrNull { it.submitSelector == action.selector } ?: continue
            val refusal = refusal(action, form)
            if (refusal != null) {
                context.notes += "Trial touch skipped '${action.name}' on ${page.urlPattern}: $refusal"
                continue
            }
            val role = action.allowedRoles.firstOrNull { it in sessions } ?: continue
            val url = accumulator.exampleUrl(page.urlPattern, role) ?: continue
            if (!context.origin.contains(url)) continue
            try {
                touch(action, page, form, role, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BrowserActionException) {
                logger.warn { "Trial touch of ${action.id} failed: ${e.message}" }
                context.notes += "Trial touch of '${action.name}' on ${page.urlPattern} failed: ${e.message}"
            }
            context.modelUpdated()
        }
    }

    private fun refusal(
        action: ActionModel,
        form: FormModel,
    ): String? =
        when {
            form.fields.any { it.type in NEVER_FILLED } -> "the form asks for a password or a file"
            LinkPolicy.looksUnsafe(form.actionPath.orEmpty(), action.name, form.purpose) -> "the form looks destructive"
            form.fields.none { it.type !in SKIPPED_TYPES } -> "the form has no field to fill"
            else -> null
        }

    private suspend fun touch(
        action: ActionModel,
        page: PageModel,
        form: FormModel,
        role: String,
        url: URI,
    ) {
        val session = sessions.getValue(role)
        val marker = "Pətək sınaq ${context.id.value.takeLast(MARKER_ID_CHARS)}-${++touched}"
        session.navigate(url.toString())
        val before =
            session
                .snapshot()
                .visibleText
                .lines()
                .toSet()
        form.fields.forEach { field -> fill(session, field, marker) }
        session.clickSelector(checkNotNull(form.submitSelector))
        val accepted = session.waitForText(marker, OWN_RESULT_TIMEOUT).found
        val after = session.snapshot()
        val messages =
            after.visibleText
                .lines()
                .map(String::trim)
                .filter { it.isNotEmpty() && it !in before && marker !in it }
                .take(MAX_MESSAGES)
        val outcome =
            when {
                accepted -> TrialOutcome.ACCEPTED
                messages.any { Keywords.containsStem(it, ERROR_WORDS) } -> TrialOutcome.REJECTED
                else -> TrialOutcome.UNCLEAR
            }
        val observers = sessions.filterKeys { it != role }
        val seenLiveBy = if (accepted) watch(observers, marker) else emptySet()
        val evidence = listOfNotNull(context.capture.screenshot(session, role))
        val urlAfter = runCatching { UrlPatterns.of(session.currentUrl()) }.getOrNull()
        context.accumulator.recordTrial(action.id, TrialTouch(role, outcome, marker, messages, urlAfter, seenLiveBy, evidence))
        if (accepted && observers.isNotEmpty() && seenLiveBy.isEmpty()) {
            context.raiseUnknown(
                "After '${action.name}' as $role, no other role (${observers.keys.sorted().joinToString()}) saw the new item " +
                    "live within ${context.settings.liveEffectTimeout.inWholeSeconds} s. Should they receive it live?",
                "Trial touch on ${page.urlPattern} with the text '$marker'.",
                page.id,
                Provenance.OBSERVED,
                evidence,
            )
        }
        logger.info { "Trial touch of ${action.id} as $role: $outcome, seen live by $seenLiveBy" }
    }

    private suspend fun fill(
        session: BrowserSession,
        field: FieldModel,
        marker: String,
    ) {
        val value = valueFor(field, marker) ?: return
        if (field.type == "select") session.selectSelector(field.selector, value) else session.fillSelector(field.selector, value)
    }

    /** Harmless data for a field, or null to leave it as it is. */
    private fun valueFor(
        field: FieldModel,
        marker: String,
    ): String? {
        val today =
            clock
                .now()
                .wall
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
        return when (field.type) {
            "email" -> "explorer+$touched@example.com"
            "tel" -> "+994500000000"
            "number", "range" -> "1"
            "url" -> "https://example.com"
            "date" -> today.toString()
            "datetime-local" -> "${today}T10:00"
            "time" -> "10:00"
            "month" -> today.toString().take(MONTH_CHARS)
            "color" -> "#336699"
            "select" -> field.options.firstOrNull()
            "textarea" -> "$marker: Pətək kəşfiyyatçısının sınaq qeydi"
            in SKIPPED_TYPES -> null
            else -> marker
        }
    }

    /** Roles whose open page shows [marker] within the live-effect timeout, watched in parallel. */
    private suspend fun watch(
        observers: Map<String, BrowserSession>,
        marker: String,
    ): Set<String> =
        coroutineScope {
            observers
                .map { (role, session) ->
                    async {
                        val seen =
                            try {
                                session.waitForText(marker, context.settings.liveEffectTimeout).found
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: BrowserActionException) {
                                logger.debug { "Watching $role for the trial marker failed: ${e.message}" }
                                false
                            }
                        role.takeIf { seen }
                    }
                }.awaitAll()
                .filterNotNull()
                .toSortedSet()
        }

    private companion object {
        val NEVER_FILLED = setOf("password", "file")
        val SKIPPED_TYPES = setOf("checkbox", "radio", "hidden", "submit", "button", "reset", "image", "week")
        val ERROR_WORDS =
            setOf(
                "error",
                "invalid",
                "required",
                "must",
                "cannot",
                "failed",
                "wrong",
                "not allowed",
                "xəta",
                "səhv",
                "tələb",
                "düzgün",
                "mütləq",
                "mümkün",
                "uğursuz",
                "boş",
                "icazə",
            )
        val OWN_RESULT_TIMEOUT = 5.seconds
        const val MAX_MESSAGES = 5
        const val MARKER_ID_CHARS = 6
        const val MONTH_CHARS = 7
    }
}
