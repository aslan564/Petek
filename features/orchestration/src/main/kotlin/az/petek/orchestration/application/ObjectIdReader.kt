package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.browser.domain.BrowserSession
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.TemplateContext
import az.petek.campaign.domain.TemplateRenderer
import az.petek.oracle.domain.JsonFieldSelector
import az.petek.oracle.domain.TargetOracle
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where an emitted object's id came from. [source] is the evidence label (`url_regex`, `oracle`, `dom`,
 * `agent_report`); [problem] explains why a configured source could not deliver the id (the event then carries the
 * agent-reported id, labelled as such); [note] is informational (e.g. the oracle is not configured).
 */
internal data class ObjectIdResolution(
    val objectId: String?,
    val source: String?,
    val problem: String? = null,
    val note: String? = null,
)

/**
 * Reads the id of the object a step created from the configured [IdSource], so that the LLM is not the source of
 * truth for ids (docs/PLAN.md "emits"). A source that yields nothing falls back to the agent's own report, labelled
 * `agent_report`, and records why. Never throws for target-side problems.
 */
internal class ObjectIdReader(
    private val oracle: TargetOracle,
    private val fields: JsonFieldSelector,
    private val renderer: TemplateRenderer,
) {
    suspend fun read(
        source: IdSource?,
        session: BrowserSession,
        outcome: ActionOutcome,
        templates: TemplateContext,
    ): ObjectIdResolution =
        when (source) {
            null, IdSource.AgentReport -> {
                agentReport(outcome)
            }

            is IdSource.UrlRegex -> {
                attempt(URL_REGEX, outcome) { fromUrl(source, session) }
            }

            is IdSource.DomAttribute -> {
                attempt(DOM, outcome) { fromDom(source, session) }
            }

            is IdSource.OracleField -> {
                if (oracle.isAvailable) {
                    attempt(ORACLE, outcome) { fromOracle(source, templates) }
                } else {
                    agentReport(outcome).copy(note = "test API not available; id reported by the agent")
                }
            }
        }

    private suspend fun attempt(
        label: String,
        outcome: ActionOutcome,
        read: suspend () -> Lookup,
    ): ObjectIdResolution {
        val lookup =
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Lookup(null, "${e::class.simpleName}: ${e.message}")
            }
        if (lookup.id != null) return ObjectIdResolution(lookup.id, label)
        val fallback = agentReport(outcome)
        return fallback.copy(problem = "$label: ${lookup.problem ?: "no id found"}")
    }

    private suspend fun fromUrl(
        source: IdSource.UrlRegex,
        session: BrowserSession,
    ): Lookup {
        val url = session.currentUrl()
        val match = Regex(source.regex).find(url) ?: return Lookup(null, "'${source.regex}' does not match $url")
        val id = match.groupValues.getOrNull(1) ?: match.value
        return Lookup(id.takeIf { it.isNotBlank() }, "empty capture group in $url")
    }

    private suspend fun fromDom(
        source: IdSource.DomAttribute,
        session: BrowserSession,
    ): Lookup {
        val value = session.readAttribute(source.selector, source.attribute)
        return Lookup(value?.takeIf { it.isNotBlank() }, "no ${source.attribute} on ${source.selector}")
    }

    private suspend fun fromOracle(
        source: IdSource.OracleField,
        templates: TemplateContext,
    ): Lookup {
        val path = renderer.render(source.path, templates)
        val response = oracle.get(path)
        if (response.status !in 200..299) return Lookup(null, "GET $path answered ${response.status}")
        val body = response.body ?: return Lookup(null, "GET $path returned no JSON")
        val value = fields.select(body, source.field)
        val text = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        return Lookup(text?.takeIf { it.isNotBlank() }, "field '${source.field}' missing in GET $path")
    }

    private fun agentReport(outcome: ActionOutcome) =
        ObjectIdResolution(outcome.objectId, if (outcome.objectId != null) AGENT_REPORT else null)

    private data class Lookup(
        val id: String?,
        val problem: String?,
    )

    companion object {
        const val URL_REGEX = "url_regex"
        const val ORACLE = "oracle"
        const val DOM = "dom"
        const val AGENT_REPORT = "agent_report"
    }
}
