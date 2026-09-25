package az.petek.explorer.application

import az.petek.browser.domain.PageSnapshot
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.FormModel
import az.petek.explorer.domain.PageAnalysis
import az.petek.explorer.domain.PageAnalysisProtocol
import az.petek.explorer.domain.PromptRedaction
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmRole
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Asks the LLM the one structured question per page ([PageAnalysisProtocol]) and validates the answer in code.
 * A page whose visible elements are exactly the same as one already analysed (same pattern, e.g. seen by another
 * role) reuses that answer instead of paying for a new call. After [ExplorerSettings.maxConsecutiveLlmFailures]
 * failed calls in a row the analyst gives up for the rest of the exploration and says why in [disabledReason]; the
 * explorer then continues with what code finds.
 */
internal class PageAnalyst(
    private val llm: LlmClient,
    private val settings: ExplorerSettings,
    private val explorationId: ExplorationId,
) {
    private val cache = HashMap<String, PageAnalysis>()
    private var consecutiveFailures = 0

    var calls = 0
        private set

    var answersRejected = 0
        private set

    var disabledReason: String? = null
        private set

    /** The validated analysis, or null when the LLM is disabled or this call failed. */
    suspend fun analyse(
        snapshot: PageSnapshot,
        urlPattern: String,
        viewer: String,
        instructions: String?,
        forms: List<FormModel>,
    ): PageAnalysis? {
        if (disabledReason != null) return null
        val key = cacheKey(snapshot, urlPattern)
        cache[key]?.let { return it }
        val shown = PromptRedaction.snapshot(snapshot, urlPattern)
        val request =
            LlmRequest(
                system = PageAnalysisProtocol.system,
                messages =
                    listOf(
                        LlmMessage(
                            LlmRole.USER,
                            PageAnalysisProtocol.userMessage(
                                snapshot = shown,
                                viewer = viewer,
                                instructions = instructions,
                                forms = forms,
                                maxElements = settings.promptMaxElements,
                                maxTextChars = settings.promptMaxTextChars,
                            ),
                        ),
                    ),
                responseSchema = PageAnalysisProtocol.schema,
                maxOutputTokens = settings.llmMaxOutputTokens,
                label = "explorer/$explorationId$urlPattern",
            )
        calls++
        val response =
            try {
                llm.complete(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(urlPattern, e)
                return null
            }
        consecutiveFailures = 0
        val visibleToModel = shown.copy(elements = shown.elements.take(settings.promptMaxElements))
        val analysis =
            PageAnalysisProtocol.parse(
                output = response.output,
                snapshot = visibleToModel,
                maxActions = settings.maxActionsPerPage,
                maxUnknowns = settings.maxUnknownsPerPage,
            )
        if (analysis.rejected.isNotEmpty()) {
            answersRejected++
            logger.info { "LLM answer for $urlPattern partly rejected: ${analysis.rejected.joinToString("; ")}" }
        }
        cache[key] = analysis
        return analysis
    }

    private fun failed(
        urlPattern: String,
        e: Exception,
    ) {
        consecutiveFailures++
        val reason = e.message ?: e::class.simpleName.orEmpty()
        logger.warn { "LLM call for $urlPattern failed ($consecutiveFailures in a row): $reason" }
        if (consecutiveFailures >= settings.maxConsecutiveLlmFailures) {
            disabledReason = "The LLM failed $consecutiveFailures times in a row (last: $reason); the rest was explored by code only."
        }
    }

    private fun cacheKey(
        snapshot: PageSnapshot,
        urlPattern: String,
    ): String =
        urlPattern + "\n" +
            snapshot.elements.joinToString("\n") { "${it.ref}|${it.role}|${it.name}|${it.testId.orEmpty()}|${it.enabled}" }
}
