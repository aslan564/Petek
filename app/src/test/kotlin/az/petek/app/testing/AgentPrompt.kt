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

package az.petek.app.testing

import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * What one decision prompt of a tester shows the model (see `PromptBuilder.user` and `PageSnapshot.render`), read the
 * way a model reads it: the task, what the last actions observed, the current page's address, its elements with their
 * values, and its visible text. The rule-based stand-ins for the AI in the e2e tests decide from it.
 */
internal class AgentPrompt(
    val task: String,
    val history: List<Entry>,
    val url: String,
    val elements: List<Element>,
    val text: String,
) {
    val path: String get() = URI(url).path.orEmpty().ifEmpty { "/" }

    fun el(testId: String): Element? = elements.firstOrNull { it.testId == testId }

    fun last(): Entry? = history.lastOrNull()

    /** Whether a control named [name] was clicked and the page took the click. */
    fun clicked(name: String): Boolean = clicks(name) > 0

    /** How many times a control named [name] was clicked and the page took the click. */
    fun clicks(name: String): Int =
        history.count {
            it.action.startsWith("click") && "\"$name\"" in it.action &&
                it.observation.startsWith("OK")
        }

    data class Entry(
        val action: String,
        val observation: String,
    )

    data class Element(
        val ref: Int,
        val role: String,
        val name: String,
        val testId: String?,
        val value: String?,
    )

    companion object {
        fun of(request: LlmRequest): AgentPrompt {
            val lines =
                request.messages
                    .last { it.role == LlmRole.USER }
                    .content
                    .lines()
            val page = lines.indexOfFirst { it == "Current page:" }
            val elements = lines.indexOfFirst { it == "Elements:" }
            val text = lines.indexOfFirst { it == "Visible text:" }
            return AgentPrompt(
                task = lines.first { it.startsWith("Task: ") }.removePrefix("Task: "),
                history =
                    lines.take(page).mapNotNull { line ->
                        HISTORY.matchEntire(line)?.let { Entry(it.groupValues[1], it.groupValues[2]) }
                    },
                url = lines.first { it.startsWith("URL: ") }.removePrefix("URL: "),
                elements = elementsOf(lines.subList(elements + 1, text)),
                text = lines.drop(text + 1).joinToString("\n"),
            )
        }

        /** The element lines of a rendered page (`[3] button "Save" (testid=note-submit)`). */
        fun elementsOf(lines: List<String>): List<Element> =
            lines.mapNotNull { line ->
                ELEMENT.matchEntire(line)?.let {
                    Element(
                        ref = it.groupValues[1].toInt(),
                        role = it.groupValues[2],
                        name = it.groupValues[3],
                        testId = it.groupValues[4].ifEmpty { null },
                        value = it.groups[5]?.value,
                    )
                }
            }

        private val HISTORY = Regex("""\d+\. (.*?) -> (.*)""")
        private val ELEMENT = Regex("""\[(\d+)] (\S+) "(.*?)"(?: \(testid=([^)]+)\))?(?: value="(.*)")?(?: \[disabled])?""")
    }
}

/** The agent's own tools, answered as a model answers with them. */
internal object AgentAnswers {
    fun click(
        element: AgentPrompt.Element,
        reason: String,
    ) = answer("click", reason) { put("ref", element.ref) }

    fun type(
        element: AgentPrompt.Element,
        text: String,
        submit: Boolean = false,
    ) = answer("type", "filling ${element.testId ?: element.name}") {
        put("ref", element.ref)
        put("text", text)
        put("submit", submit)
    }

    fun select(
        element: AgentPrompt.Element,
        option: String,
    ) = answer("select", "choosing ${element.testId ?: element.name}") {
        put("ref", element.ref)
        put("option", option)
    }

    fun navigate(
        url: String,
        reason: String,
    ) = answer("navigate", reason) { put("url", url) }

    fun done(
        summary: String,
        objectId: String? = null,
    ) = answer("done", "the task is complete") {
        put("summary", summary)
        put("success", true)
        objectId?.let { put("object_id", it) }
    }

    fun problem(
        kind: String,
        note: String,
    ) = answer("report_problem", note) {
        put("kind", kind)
        put("note", note)
    }

    fun answer(
        tool: String,
        reason: String,
        fields: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        buildJsonObject {
            put("reason", reason)
            put("tool", tool)
            fields()
        }
}
