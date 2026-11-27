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

import az.petek.llm.testing.ScriptedLlmClient
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

private typealias Prompt = AgentPrompt
private typealias Element = AgentPrompt.Element

/**
 * A rule-based stand-in for the testers' AI on the contract site (docs/TARGET_CONTRACT.md), so the contract demo
 * (`scenarios/contract-demo.yaml`) runs end to end without a real model. Every answer is read off the prompt the way a
 * model reads it (the task, the current page's elements and their values, what its last actions observed) and is one
 * of the agent's own tools, so the harness, the browser, the site and every check work for real; only the model's
 * judgement is replaced by rules. It knows the contract's pages, not the run: ids, codes and people come from the
 * page, the tools and the harness's placeholders, as they come to a model. A task it has no rule for is reported as a
 * problem, never guessed.
 */
internal class ContractSiteDriver {
    /** Titles of the tickets created in this run, so later steps find "the ticket" on the list as a colleague would. */
    private val tickets = ConcurrentHashMap.newKeySet<String>()

    val client: ScriptedLlmClient = ScriptedLlmClient { request -> decide(AgentPrompt.of(request)) }

    private fun decide(prompt: Prompt): JsonObject {
        val task = prompt.task.lowercase()
        return when {
            "qeydiyyatdan keç" in task -> signUp(prompt)
            task.startsWith("elan yarat") -> announce(prompt)
            "bildirişləri aç" in task -> readAnnouncements(prompt)
            "ticket yaz" in task -> createTicket(prompt)
            "in-progress" in task -> startAndAssign(prompt)
            "approve etməyə çalış" in task -> tryToApprove(prompt)
            "approve et" in task -> approve(prompt)
            else -> problem("other", "the contract driver has no rule for the task '${prompt.task}'")
        }
    }

    // --- tasks ---------------------------------------------------------------------------------------------------

    /** The owner's sign-up: the form, the e-mailed code, the phone code, then the signed-in home page. */
    private fun signUp(prompt: Prompt): JsonObject {
        // The signed-in header offers "log out"; the user's name there is text, not a control.
        if (prompt.el(LOGOUT) != null) return done("signed up, verified and the company is created")
        return when (prompt.path) {
            "/register" -> {
                val company = quoted(prompt.task) ?: "Pətək Test MMC"
                fill(
                    prompt,
                    listOf(
                        "register-name" to "{self.name}",
                        "register-email" to "{self.email}",
                        "register-phone" to "{self.phone}",
                        "register-password" to "{self.password}",
                        "register-company" to company,
                    ),
                ) ?: click(prompt, "register-submit")
            }

            "/verify" -> {
                code(prompt, "get_email_code", "verify-code", "{vars.email_code}")
            }

            "/verify/phone" -> {
                code(prompt, "get_phone_code", "verify-phone-code", "{vars.phone_code}")
            }

            else -> {
                navigate("/register", "sign-up starts on the sign-up page")
            }
        }
    }

    /** Asks the harness for the code once, then types it and submits. */
    private fun code(
        prompt: Prompt,
        tool: String,
        field: String,
        placeholder: String,
    ): JsonObject {
        val asked = prompt.last()?.let { it.action == tool && it.observation.startsWith("OK") } == true
        if (!asked) return answer(tool, "the code must be read before it is typed")
        val input = prompt.el(field) ?: return problem("unexpected_ui", "the page has no $field field")
        return type(input, placeholder, submit = true)
    }

    private fun announce(prompt: Prompt): JsonObject {
        val title = quoted(prompt.task) ?: return problem("other", "the task names no announcement title")
        if (prompt.path.startsWith("/announcements/") && title in prompt.text) {
            return done("announcement '$title' is published", objectId = prompt.path.substringAfterLast('/'))
        }
        if (prompt.path != "/announcements") return navigate("/announcements", "announcements are made on their page")
        return fill(prompt, listOf("announcement-title" to title, "announcement-body" to "Hamı iştirak etsin"))
            ?: click(prompt, "announcement-submit")
    }

    /** Opens the notifications from the bell, then the announcements, which counts as reading them. */
    private fun readAnnouncements(prompt: Prompt): JsonObject =
        when {
            prompt.path.startsWith("/announcements") -> {
                done("the new announcement is read")
            }

            prompt.path == "/notifications" -> {
                click(prompt, "nav-announcements")
            }

            else -> {
                prompt.el("notification-bell")?.let { click(it, "the notifications open from the bell") }
                    ?: navigate("/notifications", "no bell")
            }
        }

    private fun createTicket(prompt: Prompt): JsonObject {
        val title = quoted(prompt.task) ?: return problem("other", "the task names no ticket title")
        val department = DEPARTMENT.find(prompt.task)?.groupValues?.get(1) ?: return problem("other", "the task names no department")
        if (TICKET.matches(prompt.path) && title in prompt.text) {
            tickets += title
            return done("ticket '$title' is created for $department", objectId = prompt.path.substringAfterLast('/'))
        }
        if (prompt.path != "/tickets") return navigate("/tickets", "tickets are written on their page")
        val filled = fill(prompt, listOf("ticket-title" to title))
        if (filled != null) return filled
        val select = prompt.el("ticket-department") ?: return problem("unexpected_ui", "the ticket form has no department")
        if (select.value != department) return select(select, department)
        return click(prompt, "ticket-submit")
    }

    /** Sets the ticket in progress, then assigns it to the HR manager picked from the offered people. */
    private fun startAndAssign(prompt: Prompt): JsonObject {
        if (!TICKET.matches(prompt.path)) return openTicket(prompt)
        // The page keeps offering "take into progress" after it is done: the status, or its own click the page
        // accepted, says it is done (whether the site really changed it is the oracle's to say, not the tester's).
        if (IN_PROGRESS !in prompt.text && !prompt.clicked("İcraya götür")) {
            return prompt.el("ticket-set-in-progress")?.let { click(it, "the ticket is taken into progress first") }
                ?: problem("permission_denied", "the ticket cannot be taken into progress by me")
        }
        if (prompt.clicked("Təyin et")) return done("the ticket is in progress and assigned to the HR manager")
        val assignee = prompt.el("ticket-assignee") ?: return problem("permission_denied", "the ticket offers no assignment")
        if (hrManager(assignee.value.orEmpty())) return click(prompt, "ticket-assign")
        // A person is picked by a label the page offers: asked for by the department, the offered labels come back.
        val offered =
            prompt
                .last()
                ?.observation
                ?.let(::options)
                .orEmpty()
        return select(assignee, offered.firstOrNull(::hrManager) ?: "HR")
    }

    /** One racer: opens the ticket and approves it; a refusal as already decided is what a loser reports. */
    private fun approve(prompt: Prompt): JsonObject {
        if (!TICKET.matches(prompt.path)) return openTicket(prompt)
        if (prompt.clicked("Təsdiqlə")) {
            val error = prompt.el("ticket-error")?.name ?: ERROR_LINE.find(prompt.text)?.value
            return if (prompt.text.contains("approved") && error == null) {
                done("the ticket is approved")
            } else {
                problem("other", "the ticket was not approved for me: ${error ?: "the page shows no approval"}")
            }
        }
        return prompt.el("ticket-approve")?.let { click(it, "approving the ticket") }
            ?: problem("permission_denied", "the ticket offers me no approval")
    }

    /** Someone the site must refuse: tries only what the page offers, once, and says what came of it. */
    private fun tryToApprove(prompt: Prompt): JsonObject {
        if (TICKET.matches(prompt.path)) {
            if (prompt.clicked("Təsdiqlə")) {
                val error = ERROR_LINE.find(prompt.text)?.value?.trim()
                return if (error == null) done("the page let me approve the ticket") else problem("permission_denied", error)
            }
            return prompt.el("ticket-approve")?.let { click(it, "the page offers an approval") }
                ?: problem("permission_denied", "the ticket page offers me no approval")
        }
        if (prompt.path != "/tickets") return navigate("/tickets", "tickets are on their page")
        return ticketLink(prompt)?.let { click(it, "opening the ticket") }
            ?: problem("permission_denied", "no ticket I may approve is listed for me")
    }

    private fun openTicket(prompt: Prompt): JsonObject {
        if (prompt.path != "/tickets") return navigate("/tickets", "the ticket is on the ticket list")
        return ticketLink(prompt)?.let { click(it, "opening the ticket") } ?: problem("blocked", "the ticket is not on my list")
    }

    private fun ticketLink(prompt: Prompt): Element? = prompt.elements.firstOrNull { it.role == "link" && it.name in tickets }

    // --- answers -------------------------------------------------------------------------------------------------

    /** Types the first of [fields] whose value is still empty; null when every one has a value. */
    private fun fill(
        prompt: Prompt,
        fields: List<Pair<String, String>>,
    ): JsonObject? =
        fields.firstNotNullOfOrNull { (testId, text) ->
            prompt.el(testId)?.takeIf { it.value.isNullOrEmpty() }?.let { type(it, text) }
        }

    private fun click(
        prompt: Prompt,
        testId: String,
    ): JsonObject =
        prompt.el(testId)?.let { click(it, "the next control of the task") } ?: problem("unexpected_ui", "the page has no $testId")

    private fun click(
        element: Element,
        reason: String,
    ) = AgentAnswers.click(element, reason)

    private fun type(
        element: Element,
        text: String,
        submit: Boolean = false,
    ) = AgentAnswers.type(element, text, submit)

    private fun select(
        element: Element,
        option: String,
    ) = AgentAnswers.select(element, option)

    private fun navigate(
        url: String,
        reason: String,
    ) = AgentAnswers.navigate(url, reason)

    private fun done(
        summary: String,
        objectId: String? = null,
    ) = AgentAnswers.done(summary, objectId)

    private fun problem(
        kind: String,
        note: String,
    ) = AgentAnswers.problem(kind, note)

    private fun answer(
        tool: String,
        reason: String,
    ) = AgentAnswers.answer(tool, reason)

    private companion object {
        const val LOGOUT = "logout"
        const val IN_PROGRESS = "in_progress"
        val MANAGER = Regex("(?i)menecer|manager")
        val TICKET = Regex("/tickets/[^/]+(/[a-z-]+)?")
        val DEPARTMENT = Regex("""(\S+) departamentinə""")
        val QUOTED = Regex("'([^']+)'")

        /** A refusal the ticket page shows (`ticket-error` is text, not a control): already decided, not allowed. */
        val ERROR_LINE = Regex("(?i)[^\n]*(artıq|icazə|already|not allowed)[^\n]*")
        const val AVAILABLE = "available options"
        val LABEL = Regex("\"([^\"]*)\"")

        fun quoted(task: String): String? = QUOTED.find(task)?.groupValues?.get(1)

        /** An assignee label of the HR department's manager, as the ticket page writes it: `<name> — Menecer, HR`. */
        fun hrManager(label: String): Boolean = label.endsWith(", HR") && MANAGER.containsMatchIn(label)

        /** The option labels a failed `select` listed: `… available options, those containing it first: "A", "B"`. */
        fun options(observation: String): List<String> =
            observation
                .substringAfter(AVAILABLE, "")
                .let { listed -> LABEL.findAll(listed).map { it.groupValues[1] }.toList() }
    }
}
