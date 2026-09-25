package az.petek.agent.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration.Companion.seconds

/**
 * The flat JSON decision shape: `{"reason": str, "tool": enum, ...optional tool arguments}`.
 *
 * The schema is flat (one object, every argument optional): small, identical for every call (cacheable) and
 * strict-compatible (`additionalProperties: false`, no numeric bounds, which strict mode does not support). Per-tool
 * rules therefore live in [parse]: the schema only narrows what the model can say, the parser decides what is
 * allowed (CLAUDE.md rule 3). The parser is deliberately tolerant of harmless
 * encoding differences (a JSON `null` means "absent", `"12"` is accepted for an integer, `"true"` for a boolean)
 * and strict about meaning: a missing or out-of-range argument yields [DecisionParse.Invalid] with a message the
 * agent loop feeds back to the model verbatim.
 */
class JsonDecisionProtocol : DecisionProtocol {
    override val toolNames: List<String> = TOOL_NAMES

    override fun responseSchema(): JsonObject = SCHEMA

    override fun describeTools(): String = TOOL_REFERENCE

    override fun parse(output: JsonObject): DecisionParse =
        try {
            DecisionParse.Valid(decode(Fields(output)))
        } catch (e: InvalidDecisionException) {
            DecisionParse.Invalid(e.message)
        }

    private fun decode(fields: Fields): AgentDecision {
        val tool =
            fields
                .string(TOOL)
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: invalid("Missing required field 'tool'. Use one of: ${TOOL_NAMES.joinToString()}.")
        if (tool !in TOOL_NAMES) invalid("Unknown tool '$tool'. Use one of: ${TOOL_NAMES.joinToString()}.")
        val reason =
            fields.string(REASON)?.trim()?.takeIf { it.isNotEmpty() }
                ?: invalid("Missing required field 'reason': say briefly why you choose this action.")
        return AgentDecision(reason, action(tool, fields))
    }

    private fun action(
        tool: String,
        fields: Fields,
    ): AgentAction =
        when (tool) {
            NAVIGATE -> {
                AgentAction.Navigate(url(fields))
            }

            CLICK -> {
                AgentAction.Click(ref(tool, fields))
            }

            TYPE -> {
                AgentAction.Type(
                    ref = ref(tool, fields),
                    text = fields.string(ARG_TEXT) ?: missing(tool, ARG_TEXT, "the text to type"),
                    submit = fields.boolean(ARG_SUBMIT) ?: false,
                )
            }

            SELECT -> {
                AgentAction.Select(ref(tool, fields), required(tool, fields, ARG_OPTION, "the option label or value"))
            }

            READ_TEXT -> {
                AgentAction.ReadText(required(tool, fields, ARG_SELECTOR, "a CSS selector such as [data-testid=\"ticket-status\"]"))
            }

            WAIT_TEXT -> {
                AgentAction.WaitText(required(tool, fields, ARG_TEXT, "the text to wait for"), waitTimeout(fields))
            }

            GET_EMAIL_CODE -> {
                AgentAction.GetEmailCode
            }

            DONE -> {
                AgentAction.Done(
                    summary = required(tool, fields, ARG_SUMMARY, "a short factual summary of the result"),
                    success = fields.boolean(ARG_SUCCESS) ?: true,
                    objectId = fields.string(ARG_OBJECT_ID)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }

            REPORT_PROBLEM -> {
                AgentAction.ReportProblem(problemKind(fields), required(tool, fields, ARG_NOTE, "what went wrong"))
            }

            else -> {
                invalid("Unknown tool '$tool'.")
            }
        }

    private fun ref(
        tool: String,
        fields: Fields,
    ): Int {
        val ref = fields.int(ARG_REF) ?: missing(tool, ARG_REF, "the element number from the Elements list")
        if (ref < 1) invalid("'$ARG_REF' must be an element number ≥ 1 from the Elements list, was $ref.")
        return ref
    }

    private fun url(fields: Fields): String {
        val url = required(NAVIGATE, fields, ARG_URL, "a path such as /tickets or an absolute http(s) URL")
        if (!isNavigable(url)) {
            invalid("'$ARG_URL' must be a path starting with '/' (e.g. /tickets) or an absolute http(s) URL, was '$url'.")
        }
        return url
    }

    private fun isNavigable(url: String): Boolean {
        if (url.startsWith("/")) return !url.startsWith("//") && url.none { it.isWhitespace() }
        return try {
            val uri = URI(url)
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
        } catch (_: URISyntaxException) {
            false
        }
    }

    private fun waitTimeout(fields: Fields) =
        (fields.int(ARG_TIMEOUT_S) ?: DEFAULT_WAIT_SECONDS).coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS).seconds

    private fun problemKind(fields: Fields): ProblemKind {
        val key =
            fields
                .string(ARG_KIND)
                ?.trim()
                ?.lowercase()
                ?.replace('-', '_')
                ?.replace(' ', '_')
        return if (key.isNullOrEmpty()) ProblemKind.OTHER else ProblemKind.fromKey(key)
    }

    private fun required(
        tool: String,
        fields: Fields,
        name: String,
        meaning: String,
    ): String = fields.string(name)?.trim()?.takeIf { it.isNotEmpty() } ?: missing(tool, name, meaning)

    private fun missing(
        tool: String,
        name: String,
        meaning: String,
    ): Nothing = invalid("Tool '$tool' needs '$name' ($meaning).")

    /** Typed, null-tolerant access to the model's answer. Wrong types are reported, never coerced silently. */
    private class Fields(
        private val json: JsonObject,
    ) {
        private fun primitive(name: String): JsonPrimitive? =
            when (val value: JsonElement? = json[name]) {
                null, JsonNull -> null
                is JsonPrimitive -> value
                is JsonObject, is JsonArray -> invalid("'$name' must be a single value, not an object or a list.")
            }

        fun string(name: String): String? = primitive(name)?.content

        fun int(name: String): Int? {
            val value = primitive(name) ?: return null
            val number = value.content.trim().toDoubleOrNull()
            if (number == null || number % 1.0 != 0.0 || number !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                invalid("'$name' must be an integer, was ${value.render()}.")
            }
            return number.toInt()
        }

        fun boolean(name: String): Boolean? {
            val value = primitive(name) ?: return null
            return value.booleanOrNull ?: invalid("'$name' must be true or false, was ${value.render()}.")
        }

        private fun JsonPrimitive.render() = if (isString) "\"$content\"" else content
    }

    private class InvalidDecisionException(
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        const val NAVIGATE = "navigate"
        const val CLICK = "click"
        const val TYPE = "type"
        const val SELECT = "select"
        const val READ_TEXT = "read_text"
        const val WAIT_TEXT = "wait_text"
        const val GET_EMAIL_CODE = "get_email_code"
        const val DONE = "done"
        const val REPORT_PROBLEM = "report_problem"

        /** Every tool the model may name, in the order the prompt lists them. */
        val TOOL_NAMES: List<String> =
            listOf(NAVIGATE, CLICK, TYPE, SELECT, READ_TEXT, WAIT_TEXT, GET_EMAIL_CODE, DONE, REPORT_PROBLEM)

        const val DEFAULT_WAIT_SECONDS = 10
        const val MIN_WAIT_SECONDS = 1
        const val MAX_WAIT_SECONDS = 60

        private const val REASON = "reason"
        private const val TOOL = "tool"
        private const val ARG_REF = "ref"
        private const val ARG_TEXT = "text"
        private const val ARG_URL = "url"
        private const val ARG_SELECTOR = "selector"
        private const val ARG_OPTION = "option"
        private const val ARG_SUBMIT = "submit"
        private const val ARG_TIMEOUT_S = "timeout_s"
        private const val ARG_SUMMARY = "summary"
        private const val ARG_SUCCESS = "success"
        private const val ARG_OBJECT_ID = "object_id"
        private const val ARG_KIND = "kind"
        private const val ARG_NOTE = "note"

        private fun invalid(message: String): Nothing = throw InvalidDecisionException(message)

        private val SCHEMA: JsonObject =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    property(REASON, "string", "Why this action moves the task forward (one sentence).")
                    putJsonObject(TOOL) {
                        put("type", "string")
                        putJsonArray("enum") { TOOL_NAMES.forEach { add(it) } }
                        put("description", "The one tool to use this turn.")
                    }
                    property(ARG_REF, "integer", "click/type/select: element number from the Elements list.")
                    property(ARG_TEXT, "string", "type: text to enter (placeholders allowed); wait_text: text to wait for.")
                    property(ARG_URL, "string", "navigate: a path such as /tickets or an absolute http(s) URL of the same site.")
                    property(ARG_SELECTOR, "string", "read_text: CSS selector of the element to read.")
                    property(ARG_OPTION, "string", "select: label or value of the option to choose.")
                    property(ARG_SUBMIT, "boolean", "type: press Enter after typing (default false).")
                    property(ARG_TIMEOUT_S, "integer", "wait_text: seconds to wait, 1-60 (default 10).")
                    property(ARG_SUMMARY, "string", "done: short factual summary of the result.")
                    property(ARG_SUCCESS, "boolean", "done: whether the task was achieved (default true).")
                    property(ARG_OBJECT_ID, "string", "done: id of the object you created, when the page shows it.")
                    putJsonObject(ARG_KIND) {
                        put("type", "string")
                        putJsonArray("enum") { ProblemKind.entries.forEach { add(it.key) } }
                        put("description", "report_problem: kind of problem.")
                    }
                    property(ARG_NOTE, "string", "report_problem: what went wrong.")
                }
                putJsonArray("required") {
                    add(REASON)
                    add(TOOL)
                }
                put("additionalProperties", false)
            }

        private fun JsonObjectBuilder.property(
            name: String,
            type: String,
            description: String,
        ) {
            putJsonObject(name) {
                put("type", type)
                put("description", description)
            }
        }

        private val TOOL_REFERENCE: String =
            """
            Answer with exactly one JSON object: {"reason": "<why>", "tool": "<tool name>", ...arguments of that tool}.
            Tools:
            - $NAVIGATE(url): open a page of the site by path, e.g. "/tickets", or by an absolute http(s) URL of the same site.
            - $CLICK(ref): click element [ref] of the Elements list.
            - $TYPE(ref, text, submit?): replace the content of input [ref] with text; submit=true presses Enter afterwards.
            - $SELECT(ref, option): choose the option with this label or value in select [ref].
            - $READ_TEXT(selector): read the text of the first element matching a CSS selector.
            - $WAIT_TEXT(text, timeout_s?): wait until text is visible on the page ($MIN_WAIT_SECONDS-$MAX_WAIT_SECONDS s, default $DEFAULT_WAIT_SECONDS).
            - $GET_EMAIL_CODE(): fetch the newest verification code e-mailed to you; afterwards type {vars.email_code}.
            - $DONE(summary, success?, object_id?): finish the task; success defaults to true; object_id = id of the object you created when the page shows it.
            - $REPORT_PROBLEM(kind, note): stop and report a problem; kind is one of ${ProblemKind.entries.joinToString(", ") { it.key }}.
            """.trimIndent()
    }
}
