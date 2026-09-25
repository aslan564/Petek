package az.petek.campaign.infrastructure

import az.petek.campaign.domain.Flow
import az.petek.campaign.domain.FlowFailure
import az.petek.campaign.domain.FlowFailureReason
import az.petek.campaign.domain.FlowStep
import az.petek.campaign.domain.JourneyPage
import az.petek.campaign.domain.LinkPurpose
import az.petek.campaign.domain.ValueTarget
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

/**
 * Reads `target_profile.flows` (see [Flow] for the meaning of each step). A flow is a list of steps; a step is either
 * a bare word (`- save_session`, `- account_created`) or a map with exactly one step key. Steps with a single natural
 * argument accept it as a plain value (`- click: login.submit`, `- wait_for: session.user_name`); everything else is a
 * map of named arguments. Unknown keys, missing arguments and wrong types are problems with lines, like the rest of
 * the campaign file.
 */
internal class FlowYamlReading(
    private val reader: YamlReader,
) {
    /** `flows:` as a map of flow names to step lists; null for a structural problem already recorded. */
    fun flows(
        node: YamlNode?,
        path: String,
    ): Map<String, Flow> {
        val plain = node.plain() ?: return emptyMap()
        if (plain !is YamlMap) return reader.problem(path, "${displayPath(path)} must be a map of flow names to step lists") ?: emptyMap()
        return plain.entries.entries
            .mapNotNull { (name, value) ->
                val flowPath = childPath(path, name.content)
                steps(value.plain(), flowPath)?.let { name.content to Flow(it) }
            }.toMap()
    }

    private fun steps(
        node: YamlNode?,
        path: String,
    ): List<FlowStep>? {
        if (node == null) return reader.problem(path, "${displayPath(path)} needs a list of steps")
        val items = reader.list(node, path) ?: return null
        val steps = items.map(::step)
        return steps.takeIf { all -> all.all { it != null } }?.filterNotNull()
    }

    private fun step(item: YamlItem): FlowStep? {
        val node = item.node ?: return reader.problem(item.path, "${displayPath(item.path)} is an empty step")
        return step(node, item.path)
    }

    private fun step(
        node: YamlNode,
        path: String,
    ): FlowStep? {
        if (node is YamlScalar) return bareStep(node.content, path)
        if (node !is YamlMap || node.entries.size != 1) {
            return reader.problem(path, "${displayPath(path)} must be one step such as '- click: login.submit' (steps: $STEP_KEYS)")
        }
        val (key, value) = node.entries.entries.single()
        val type = key.content
        val at = childPath(path, type)
        val body = value.plain()
        return when (type) {
            "save_session", "account_created" -> bareStepWithValue(type, body, at)
            "goto" -> reader.text(required(body, at), at)?.let { FlowStep.Goto(it) }
            "fill" -> fill(body, at)
            "select" -> select(body, at)
            "check" -> selectorOnly(body, at)?.let { FlowStep.Check(it) }
            "click" -> selectorOnly(body, at)?.let { FlowStep.Click(it) }
            "click_if_visible" -> selectorOnly(body, at)?.let { FlowStep.ClickIfVisible(it) }
            "assert_identity" -> selectorOnly(body, at)?.let { FlowStep.AssertIdentity(it) }
            "wait_for" -> waitFor(body, at)
            "expect_url" -> expectUrl(body, at)
            "email_link" -> emailLink(body, at)
            "email_code" -> codeStep(body, at)?.let { (selector, submit) -> FlowStep.EmailCode(selector, submit) }
            "phone_code" -> codeStep(body, at)?.let { (selector, submit) -> FlowStep.PhoneCode(selector, submit) }
            "read" -> read(body, at)
            "set_shared" -> setShared(body, at)
            "if_visible" -> ifVisible(body, at)
            "journey" -> journey(body, at)
            else -> reader.problem(at, "unknown flow step '$type' (steps: $STEP_KEYS)")
        }
    }

    private fun bareStep(
        word: String,
        path: String,
    ): FlowStep? =
        when (word.trim()) {
            "save_session" -> FlowStep.SaveSession
            "account_created" -> FlowStep.AccountCreated
            else -> reader.problem(path, "'$word' is not a step without arguments (save_session, account_created); other steps are maps")
        }

    /** `- save_session:` or `- save_session: true` read like the bare word. */
    private fun bareStepWithValue(
        type: String,
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        if (body != null && reader.bool(body, path) != true) return reader.problem(path, "${displayPath(path)} takes no arguments")
        return bareStep(type, path)
    }

    private fun required(
        body: YamlNode?,
        path: String,
    ): YamlNode? = body ?: reader.problem(path, "${displayPath(path)} has no value")

    /** A selector given directly (`click: login.submit`) or as `{selector: …}`. */
    private fun selectorOnly(
        body: YamlNode?,
        path: String,
    ): String? {
        val node = required(body, path) ?: return null
        if (node is YamlScalar) return node.content
        return reader.map(node, path, SELECTOR_KEYS)?.text("selector", required = true)
    }

    private fun fill(
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        val fields = reader.map(required(body, path), path, FILL_KEYS) ?: return null
        val selector = fields.text("selector", required = true)
        val value = fields.text("value", required = true)
        return if (selector != null && value != null) FlowStep.Fill(selector, value) else null
    }

    private fun select(
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        val fields = reader.map(required(body, path), path, SELECT_KEYS) ?: return null
        val selector = fields.text("selector", required = true)
        val option = fields.text("option", required = true)
        return if (selector != null && option != null) FlowStep.Select(selector, option) else null
    }

    private fun waitFor(
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        val node = required(body, path) ?: return null
        if (node is YamlScalar) return FlowStep.WaitFor(listOf(node.content), null)
        val fields = reader.map(node, path, WAIT_FOR_KEYS) ?: return null
        val given = listOf("selector", "any", "text").filter { fields.has(it) }
        if (given.size != 1) return reader.problem(path, "${displayPath(path)} needs exactly one of selector, any or text")
        val selectors =
            when {
                fields.has("selector") -> fields.text("selector")?.let(::listOf)
                fields.has("any") -> anySelectors(fields)
                else -> emptyList()
            }
        val text = fields.text("text")
        val timeout = fields.seconds("timeout_s")
        val failure = fields["fail"]?.let { failure(it, fields.pathOf("fail")) }
        if (fields.has("fail") && failure == null) return null
        return selectors?.let { FlowStep.WaitFor(it, text, timeout, failure) }
    }

    private fun anySelectors(fields: YamlFields): List<String>? {
        val selectors = fields.textList("any") ?: return null
        val path = fields.pathOf("any")
        return selectors.ifEmpty { reader.problem(path, "${displayPath(path)} needs at least one selector") }
    }

    private fun expectUrl(
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        val node = required(body, path) ?: return null
        if (node is YamlScalar) return FlowStep.ExpectUrl(node.content)
        val fields = reader.map(node, path, EXPECT_URL_KEYS) ?: return null
        val regex = fields.text("regex", required = true)
        val timeout = fields.seconds("timeout_s")
        val failure = fields["fail"]?.let { failure(it, fields.pathOf("fail")) }
        if (fields.has("fail") && failure == null) return null
        return regex?.let { FlowStep.ExpectUrl(it, timeout, failure) }
    }

    private fun emailLink(
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        val node = required(body, path) ?: return null
        if (node is YamlScalar) return purpose(node.content, path)?.let { FlowStep.EmailLink(it) }
        val fields = reader.map(node, path, EMAIL_LINK_KEYS) ?: return null
        val purpose = fields.text("purpose")?.let { purpose(it, fields.pathOf("purpose")) ?: return null } ?: LinkPurpose.ANY
        val pattern = fields.text("pattern")
        val open = fields.bool("open") ?: true
        val into = fields.text("into")?.let { target(it, fields.pathOf("into")) ?: return null }
        return FlowStep.EmailLink(purpose, pattern, open, into)
    }

    private fun purpose(
        raw: String,
        path: String,
    ): LinkPurpose? =
        LinkPurpose.fromKey(raw)
            ?: reader.problem(path, "${displayPath(path)} must be one of ${LinkPurpose.entries.joinToString(", ") { it.key }}, was '$raw'")

    /** `email_code: verify.code` or `{selector, submit}`. */
    private fun codeStep(
        body: YamlNode?,
        path: String,
    ): Pair<String, String?>? {
        val node = required(body, path) ?: return null
        if (node is YamlScalar) return node.content to null
        val fields = reader.map(node, path, CODE_KEYS) ?: return null
        val selector = fields.text("selector", required = true) ?: return null
        return selector to fields.text("submit")
    }

    private fun read(
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        val fields = reader.map(required(body, path), path, READ_KEYS) ?: return null
        val selector = fields.text("selector", required = true)
        val into = fields.text("into", required = true)?.let { target(it, fields.pathOf("into")) }
        val regex = fields.text("regex")
        return if (selector != null && into != null) FlowStep.Read(selector, into, regex) else null
    }

    private fun setShared(
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        val fields = reader.map(required(body, path), path, SET_SHARED_KEYS) ?: return null
        val key = fields.text("key", required = true)
        val value = fields.text("value", required = true)
        return if (key != null && value != null) FlowStep.SetShared(key.trim(), value) else null
    }

    private fun ifVisible(
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        val fields = reader.map(required(body, path), path, IF_VISIBLE_KEYS) ?: return null
        val selector = fields.text("selector", required = true)
        val then = fields.required("then")?.let { steps(it, fields.pathOf("then")) }
        val timeout = fields.seconds("timeout_s")
        return if (selector != null && then != null) FlowStep.IfVisible(selector, then, timeout) else null
    }

    private fun journey(
        body: YamlNode?,
        path: String,
    ): FlowStep? {
        val fields = reader.map(required(body, path), path, JOURNEY_KEYS) ?: return null
        val label = fields.text("label", required = true)
        val until = fields.text("until", required = true)
        val start = fields.text("start")
        val maxVisits = fields.int("max_visits", required = false) ?: FlowStep.Journey.DEFAULT_MAX_VISITS
        val pages = reader.list(fields.required("pages"), fields.pathOf("pages"))?.map { page(it) }
        if (pages == null || pages.any { it == null }) return null
        return if (label != null && until != null) FlowStep.Journey(label, until, pages.filterNotNull(), start, maxVisits) else null
    }

    private fun page(item: YamlItem): JourneyPage? {
        val fields = reader.map(item.node ?: return reader.problem(item.path, "${displayPath(item.path)} is empty"), item.path, PAGE_KEYS)
        fields ?: return null
        val label = fields.text("label", required = true)
        val selector = fields.text("when", required = true)
        val steps = fields.required("steps")?.let { steps(it, fields.pathOf("steps")) }
        val reason = fields.text("reason")?.let { reason(it, fields.pathOf("reason")) ?: return null }
        val stuck = fields["stuck"]?.let { failure(it, fields.pathOf("stuck")) ?: return null }
        if (label == null || selector == null || steps == null) return null
        return JourneyPage(label, selector, steps, reason ?: FlowFailureReason.REGISTRATION_FAILED, stuck)
    }

    private fun failure(
        node: YamlNode,
        path: String,
    ): FlowFailure? {
        val fields = reader.map(node, path, FAILURE_KEYS) ?: return null
        val reason = fields.text("reason")?.let { reason(it, fields.pathOf("reason")) ?: return null }
        val message = fields.text("message")
        val error = fields.text("error")
        if (reason == null && message == null && error == null) {
            return reader.problem(path, "${displayPath(path)} needs at least one of ${FAILURE_KEYS.joinToString(", ")}")
        }
        return FlowFailure(reason, message, error)
    }

    private fun reason(
        raw: String,
        path: String,
    ): FlowFailureReason? =
        FlowFailureReason.fromKey(raw) ?: reader.problem(path, "${displayPath(path)} must be one of ${FlowFailureReason.KEYS}, was '$raw'")

    private fun target(
        raw: String,
        path: String,
    ): ValueTarget? =
        ValueTarget.parse(raw)
            ?: reader.problem(
                path,
                "${displayPath(path)} must be vars.<key>, shared.<key> or <key> (lower-case letters, digits, '_'), was '$raw'",
            )

    private companion object {
        val STEP_KEYS: String =
            listOf(
                "goto",
                "fill",
                "select",
                "check",
                "click",
                "click_if_visible",
                "wait_for",
                "expect_url",
                "email_link",
                "email_code",
                "phone_code",
                "read",
                "set_shared",
                "if_visible",
                "save_session",
                "account_created",
                "assert_identity",
                "journey",
            ).joinToString(", ")
        val SELECTOR_KEYS = linkedSetOf("selector")
        val FILL_KEYS = linkedSetOf("selector", "value")
        val SELECT_KEYS = linkedSetOf("selector", "option")
        val WAIT_FOR_KEYS = linkedSetOf("selector", "any", "text", "timeout_s", "fail")
        val EXPECT_URL_KEYS = linkedSetOf("regex", "timeout_s", "fail")
        val EMAIL_LINK_KEYS = linkedSetOf("purpose", "pattern", "open", "into")
        val CODE_KEYS = linkedSetOf("selector", "submit")
        val READ_KEYS = linkedSetOf("selector", "into", "regex")
        val SET_SHARED_KEYS = linkedSetOf("key", "value")
        val IF_VISIBLE_KEYS = linkedSetOf("selector", "then", "timeout_s")
        val JOURNEY_KEYS = linkedSetOf("label", "until", "start", "max_visits", "pages")
        val PAGE_KEYS = linkedSetOf("label", "when", "steps", "reason", "stuck")
        val FAILURE_KEYS = linkedSetOf("reason", "message", "error")
    }
}
