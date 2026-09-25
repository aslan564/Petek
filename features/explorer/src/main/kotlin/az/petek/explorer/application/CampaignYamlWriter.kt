package az.petek.explorer.application

import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import kotlin.time.Duration

/** YAML text of a campaign plus the line each step starts at (for validation messages). */
internal data class WrittenCampaign(
    val yaml: String,
    val stepLines: Map<String, Int>,
)

/**
 * Writes a [Campaign] in the schema of `scenarios/kadrohr.yaml` (docs/PLAN.md "Ssenari formatı"). Every text is a
 * double-quoted scalar with YAML escapes, so any site text (quotes, colons, `#`, brackets) stays data. Numbers and
 * booleans are written bare; durations as seconds or milliseconds as the schema expects.
 */
internal object CampaignYamlWriter {
    fun write(
        campaign: Campaign,
        comments: List<String> = emptyList(),
    ): WrittenCampaign {
        val out = Lines()
        comments.forEach { out.add("# $it") }
        settings(out, campaign)
        targetProfile(out, campaign)
        val stepLines = LinkedHashMap<String, Int>()
        steps(out, "setup", campaign.setup, stepLines)
        steps(out, "steps", campaign.steps, stepLines)
        return WrittenCampaign(out.text(), stepLines)
    }

    private fun settings(
        out: Lines,
        campaign: Campaign,
    ) {
        val settings = campaign.settings
        out.add("campaign:")
        out.add("  name: ${quote(settings.name)}")
        out.add("  target: ${quote(settings.target.toString())}")
        out.add("  testers: ${settings.testers}")
        out.add("  seed: ${settings.seed}")
        if (settings.names.isNotEmpty()) out.add("  names: ${list(settings.names)}")
        out.add("  roles: {admin: ${settings.roles.admin}, manager: ${settings.roles.manager}, employee: ${settings.roles.employee}}")
        out.add("  departments: ${list(settings.departments)}")
        out.add("  registration: {invite: ${settings.registration.invite}, company_code: ${settings.registration.companyCode}}")
        out.add("  budget: {max_steps_per_agent: ${settings.budget.maxStepsPerAgent}, max_minutes: ${settings.budget.maxMinutes}}")
        out.add("  on_fail: ${settings.onFail.name.lowercase()}")
    }

    private fun targetProfile(
        out: Lines,
        campaign: Campaign,
    ) {
        val profile = campaign.target
        if (profile.paths.isEmpty() && profile.selectors.isEmpty() && profile.idSources.isEmpty()) return
        out.add("")
        out.add("target_profile:")
        if (profile.paths.isNotEmpty()) {
            out.add("  paths:")
            profile.paths.forEach { (key, value) -> out.add("    $key: ${quote(value)}") }
        }
        if (profile.selectors.isNotEmpty()) {
            out.add("  selectors:")
            profile.selectors.forEach { (key, value) -> out.add("    ${quote(key)}: ${quote(value)}") }
        }
        if (profile.idSources.isNotEmpty()) {
            out.add("  id_sources:")
            profile.idSources.forEach { (event, source) -> out.add("    $event: ${idSource(source)}") }
        }
    }

    private fun steps(
        out: Lines,
        key: String,
        steps: List<ScenarioStep>,
        stepLines: MutableMap<String, Int>,
    ) {
        if (steps.isEmpty()) return
        out.add("")
        out.add("$key:")
        steps.forEachIndexed { index, step ->
            if (index > 0) out.add("")
            stepLines[step.id] = out.next
            out.add("  - id: ${quote(step.id)}")
            out.add("    actor: ${quote(step.actors.raw)}")
            when (val action = step.action) {
                is StepAction.Do -> out.add("    do: ${quote(action.instruction)}")
                is StepAction.Run -> out.add("    run: ${run(action)}")
                StepAction.None -> Unit
            }
            step.emits?.let { emits ->
                val source = emits.idSource
                out.add(
                    "    emits: " +
                        if (source == null) quote(emits.event) else "{event: ${quote(emits.event)}, id_from: ${idSource(source)}}",
                )
            }
            step.waitFor?.let { out.add("    wait_for: {event: ${quote(it.event)}, timeout_s: ${seconds(it.timeout)}}") }
            if (step.parallel) out.add("    parallel: true")
            step.onFail?.let { out.add("    on_fail: ${it.name.lowercase()}") }
            if (step.assertions.isNotEmpty()) {
                out.add("    assert:")
                step.assertions.forEach { out.add("      - ${assertion(it)}") }
            }
        }
    }

    private fun run(action: StepAction.Run): String =
        if (action.args.isEmpty()) {
            quote(action.function)
        } else {
            "{function: ${quote(action.function)}, args: {" +
                action.args.entries.joinToString(", ") { "${quote(it.key)}: ${quote(it.value)}" } + "}}"
        }

    private fun idSource(source: IdSource): String =
        when (source) {
            is IdSource.UrlRegex -> "{url_regex: ${quote(source.regex)}}"
            is IdSource.OracleField -> "{oracle: {path: ${quote(source.path)}, field: ${quote(source.field)}}}"
            is IdSource.DomAttribute -> "{dom: {selector: ${quote(source.selector)}, attribute: ${quote(source.attribute)}}}"
            IdSource.AgentReport -> "{agent: true}"
        }

    private fun assertion(spec: AssertionSpec): String =
        when (spec) {
            is AssertionSpec.VisibleText -> {
                "visible_text: {text: ${quote(spec.text)}, within_s: ${seconds(spec.within)}}"
            }

            is AssertionSpec.NotVisible -> {
                "not_visible: " + (spec.selector?.let { "{selector: ${quote(it)}}" } ?: "{text: ${quote(spec.text.orEmpty())}}")
            }

            is AssertionSpec.Oracle -> {
                val fields =
                    listOfNotNull(
                        "path: ${quote(spec.path)}",
                        spec.field?.let { "field: ${quote(it)}" },
                        spec.equals?.let { "equals: ${quote(it)}" },
                        spec.contains?.let { "contains: ${quote(it)}" },
                    )
                "oracle: {${fields.joinToString(", ")}}"
            }

            is AssertionSpec.HttpStatus -> {
                "http_status: {path: ${quote(spec.path)}, method: ${quote(spec.method)}, equals: ${spec.equals}}"
            }

            is AssertionSpec.Count -> {
                "count: {selector: ${quote(spec.selector)}, equals: ${spec.equals}}"
            }

            is AssertionSpec.LatencyMax -> {
                "latency_max: {ms: ${spec.max.inWholeMilliseconds}}"
            }

            AssertionSpec.OnlyOneSucceeds -> {
                "only_one_succeeds: true"
            }
        }

    private fun list(items: List<String>): String = items.joinToString(", ", "[", "]") { quote(it) }

    private fun seconds(duration: Duration): String {
        val millis = duration.inWholeMilliseconds
        val whole = millis % MILLIS_PER_SECOND == 0L
        return if (whole) (millis / MILLIS_PER_SECOND).toString() else (millis / MILLIS_PER_SECOND.toDouble()).toString()
    }

    /** A YAML double-quoted scalar: backslash, quote and control characters escaped. */
    fun quote(text: String): String =
        buildString {
            append('"')
            text.forEach { char ->
                when {
                    char == '\\' -> append("\\\\")
                    char == '"' -> append("\\\"")
                    char == '\n' -> append("\\n")
                    char == '\t' -> append("\\t")
                    char == '\r' -> append("\\r")
                    char.code < SPACE || char.code == DELETE -> append("\\u").append(char.code.toString(HEX).padStart(4, '0'))
                    else -> append(char)
                }
            }
            append('"')
        }

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SPACE = 0x20
    private const val DELETE = 0x7f
    private const val HEX = 16

    /** Output lines with 1-based line numbers. */
    private class Lines {
        private val lines = mutableListOf<String>()

        val next: Int get() = lines.size + 1

        fun add(line: String) {
            lines += line
        }

        fun text(): String = lines.joinToString("\n", postfix = "\n")
    }
}
