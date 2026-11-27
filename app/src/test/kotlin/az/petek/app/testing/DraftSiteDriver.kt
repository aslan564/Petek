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

import az.petek.app.testing.AgentAnswers.click
import az.petek.app.testing.AgentAnswers.done
import az.petek.app.testing.AgentAnswers.navigate
import az.petek.app.testing.AgentAnswers.problem
import az.petek.app.testing.AgentAnswers.type
import az.petek.llm.domain.LlmRequest
import az.petek.llm.testing.ScriptedLlmClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap

/**
 * A rule-based stand-in for the AI on a site Pətək learned by itself (Faza 25's universal criterion), so "Test et" runs
 * end to end without a real model. It knows no site: the explorer's question about a page is answered from the page's
 * own buttons (each named as an action, its kind read off its test id), and the testers' `do` steps are read as the
 * texts the scenario composer writes (open a page, type the marker into its text fields, press the named button, once
 * or twice). Every answer is one of the agent's own tools, so the harness, the browser, the site and every check work
 * for real. A task in any other words is reported as a problem, never guessed.
 */
internal class DraftSiteDriver {
    /** The explorer's page questions asked, by the page's address pattern (to count what was asked). */
    val pagesAsked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val client: ScriptedLlmClient =
        ScriptedLlmClient { request ->
            when {
                request.label.startsWith(EXPLORER) -> page(request)
                request.label == DOCTOR -> buildJsonObject { put("ok", true) }
                else -> decide(AgentPrompt.of(request))
            }
        }

    // --- the explorer's page question ----------------------------------------------------------------------------

    private fun page(request: LlmRequest): JsonObject {
        val prompt = request.messages.last().content
        val url =
            URL
                .find(prompt)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        pagesAsked += url
        val elements = AgentPrompt.elementsOf(prompt.lines())
        return buildJsonObject {
            put("purpose", "Page $url")
            putJsonArray("actions") {
                elements.filter { it.role == BUTTON && it.testId != null }.forEach { button ->
                    addJsonObject {
                        put("ref", button.ref)
                        put("name", button.name)
                        put("kind", kindOf(checkNotNull(button.testId)))
                    }
                }
            }
            putJsonArray("unknowns") {}
        }
    }

    private fun kindOf(testId: String): String =
        when {
            "login" in testId -> "LOGIN"
            "register" in testId || "signup" in testId -> "REGISTER"
            "logout" in testId -> "OTHER"
            testId.endsWith("-submit") -> "CREATE"
            else -> "OTHER"
        }

    // --- the testers' tasks --------------------------------------------------------------------------------------

    private fun decide(prompt: AgentPrompt): JsonObject {
        CREATE.matchEntire(prompt.task)?.let { return create(prompt, it.groupValues[1], it.groupValues[2], it.groupValues[3], times = 1) }
        TWICE.matchEntire(prompt.task)?.let { return create(prompt, it.groupValues[1], it.groupValues[3], it.groupValues[2], times = 2) }
        ACT.matchEntire(prompt.task)?.let { return act(prompt, it.groupValues[1], it.groupValues[2]) }
        return problem("other", "the draft driver has no rule for the task '${prompt.task}'")
    }

    /** Opens [page], types [marker] into every empty text field and presses [button] [times] times. */
    private fun create(
        prompt: AgentPrompt,
        page: String,
        button: String,
        marker: String,
        times: Int,
    ): JsonObject {
        val pressed = prompt.clicks(button)
        if (pressed >= times || (pressed > 0 && prompt.buttonNamed(button) == null)) {
            return if (marker in prompt.text) {
                done("'$marker' is created", objectId = ID.find(prompt.path)?.groupValues?.get(1))
            } else {
                problem("other", "after '$button' the page does not show '$marker'")
            }
        }
        if (pressed == 0 && prompt.path != page) return navigate(page, "the task starts on $page")
        if (pressed == 0) {
            prompt.elements
                .firstOrNull { it.role == TEXTBOX && it.value.isNullOrEmpty() && !credential(it) }
                ?.let { return type(it, marker) }
        }
        return prompt.buttonNamed(button)?.let { click(it, "pressing '$button'") }
            ?: problem("unexpected_ui", "the page has no '$button' button")
    }

    /** Opens [page] and presses [button] once. */
    private fun act(
        prompt: AgentPrompt,
        page: String,
        button: String,
    ): JsonObject {
        if (prompt.clicked(button)) return done("'$button' is done")
        if (prompt.path != page) return navigate(page, "the task starts on $page")
        return prompt.buttonNamed(button)?.let { click(it, "pressing '$button'") }
            ?: problem("permission_denied", "the page offers me no '$button'")
    }

    private fun AgentPrompt.buttonNamed(name: String): AgentPrompt.Element? = elements.firstOrNull { it.role == BUTTON && it.name == name }

    /** A sign-in field: never where a marker text goes. */
    private fun credential(element: AgentPrompt.Element): Boolean {
        val said = "${element.testId.orEmpty()} ${element.name}".lowercase()
        return CREDENTIAL_WORDS.any { it in said }
    }

    private companion object {
        const val EXPLORER = "explorer/"
        const val DOCTOR = "doctor"
        const val BUTTON = "button"
        const val TEXTBOX = "textbox"
        val URL = Regex("""URL: (\S+)""")
        val ID = Regex("""/(\d+)/?$""")
        val CREDENTIAL_WORDS = listOf("email", "e-poçt", "password", "parol")

        // The composer's texts (ScenarioComposer): a create, a double submit, an action on a page.
        val CREATE = Regex("""(\S+) səhifəsini aç və '(.+)' ilə yeni qeyd yarat; mətn sahələrinə '(.+)' yaz""")
        val TWICE = Regex("""(\S+) səhifəsini aç, mətn sahələrinə '(.+)' yaz və '(.+)' düyməsini tez-tez iki dəfə sıx""")
        val ACT = Regex("""(\S+) səhifəsini aç və '(.+)' əməliyyatını icra et""")
    }
}
