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

package az.petek.agent.application

import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.Colleague
import az.petek.agent.domain.DecisionProtocol
import az.petek.browser.domain.PageSnapshot
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.model.WorkingLanguage
import az.petek.identity.domain.Identity

/**
 * Builds the tester persona prompts. The system prompt depends only on the agent's identity and the run's roster,
 * so it is byte-identical for every turn of an agent and can be cached by the provider; everything that changes
 * per turn (task progress, history, page) goes into the user turn. Nothing here is time-dependent.
 *
 * Secrets never appear: the password is described as the `{self.password}` placeholder, and the user turn is
 * passed through [redact] because page snapshots and observations may echo what was typed.
 *
 * No other tester appears in the prompt (Faza 18, `LINK_ONLY_SWARM.md` §3): the system prompt is the same size for 30
 * testers or 500, and a tester cannot leak what it does not know.
 */
class PromptBuilder(
    private val protocol: DecisionProtocol,
    /** How many of the most recent turns are replayed to the model. */
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
    /** What summaries and reported problems are written in ([WorkingLanguage.AUTO]: the task's own language). */
    private val language: WorkingLanguage = WorkingLanguage.AUTO,
) {
    init {
        require(historyLimit >= 1) { "historyLimit must be at least 1, was $historyLimit" }
    }

    /** What the user turn of one decision is made of. */
    data class Turn(
        val task: String,
        val history: List<ActionHistoryEntry>,
        val snapshot: PageSnapshot,
        /** 1-based number of the decision being asked for. */
        val decisionNumber: Int,
        val maxDecisions: Int,
        /** Placeholders that resolve right now, e.g. `{self.email}`, `{vars.email_code}`. */
        val placeholders: List<String>,
    )

    fun system(runtime: AgentRuntime): String =
        buildString {
            appendLine(INTRO)
            appendLine()
            appendLine("Rules:")
            (RULES + language.rule("your summaries and reported problems")).forEachIndexed { i, rule -> appendLine("${i + 1}. $rule") }
            appendLine()
            appendLine(protocol.describeTools())
            appendLine()
            appendIdentity(runtime.identity)
            appendLine()
            if (runtime.identity.registration.isCompanyMode) appendCompany(runtime) else appendSite(runtime)
        }.trimEnd()

    fun user(
        runtime: AgentRuntime,
        turn: Turn,
    ): String =
        runtime.redact(
            buildString {
                appendLine("Task: ${turn.task.trim()}")
                appendLine()
                appendLine("Decision ${turn.decisionNumber} of at most ${turn.maxDecisions}.")
                appendLine("Placeholders you can type now: ${turn.placeholders.joinToString(", ")}")
                appendLine()
                appendHistory(turn.history)
                appendLine()
                appendLine("Current page:")
                append(turn.snapshot.render())
            },
        )

    private fun StringBuilder.appendIdentity(identity: Identity) {
        appendLine("Your identity:")
        appendLine("- Name: ${identity.displayName}")
        appendLine("- Role: ${roleDescription(identity.role, identity.department)}")
        appendLine("- E-mail: ${identity.email}")
        appendLine("- Phone: ${identity.phone}")
        appendLine("- Password: secret and never shown to you; type {self.password} wherever it is needed.")
        appendLine("- Tester id: ${identity.agentId}")
    }

    /**
     * A site without companies (`tenant: none`): no company, no roster. Other testers use the same site with their
     * own accounts; a step that needs another tester's name or e-mail carries it in its task.
     */
    private fun StringBuilder.appendSite(runtime: AgentRuntime) {
        appendLine("Test context:")
        appendLine("- All accounts on the site under test used by this run are test accounts.")
        val others = runtime.roster.count { it.agentId != runtime.identity.agentId }
        if (others > 0) {
            appendLine("- $others other testers use the same site at the same time, each with their own account; never use theirs.")
        }
        appendLine("- ${gateDescription(runtime.identity)}")
    }

    private fun gateDescription(identity: Identity): String =
        when (identity.registration) {
            RegistrationMode.LOGIN -> "You sign in with an existing account (your e-mail above and {self.password})."
            RegistrationMode.GUEST -> "You are a visitor without an account; do not sign up or sign in unless the task says so."
            else -> "You sign up for your own account (your e-mail above and {self.password}) when the task needs one."
        }

    /**
     * The company the tester works in, without the people in it (Faza 18): testers do not know each other's names,
     * e-mails or roles. A step that needs another tester's name or e-mail carries it in its task
     * (`{tester.<role>.<n>.email}`), written in by the harness without saying whose it is.
     */
    private fun StringBuilder.appendCompany(runtime: AgentRuntime) {
        val departments = runtime.roster.mapNotNull { it.department }.distinct()
        appendLine("Company context:")
        appendLine("- You work in a company on the site under test; all accounts are test accounts.")
        if (departments.isNotEmpty()) appendLine("- Departments: ${departments.joinToString(", ")}")
        appendLine("- The company code, once known, can be typed as {shared.company_code}.")
        appendLine("- Other testers use the same site at the same time with their own accounts; never use theirs.")
    }

    private fun StringBuilder.appendHistory(history: List<ActionHistoryEntry>) {
        if (history.isEmpty()) {
            appendLine("Previous actions: none yet.")
            return
        }
        val recent = history.takeLast(historyLimit)
        val omitted = history.size - recent.size
        appendLine("Previous actions (oldest first${if (omitted > 0) "; $omitted earlier ones omitted" else ""}):")
        recent.forEach { appendLine("${it.number}. ${it.action} -> ${it.observation}") }
    }

    private fun roleDescription(
        role: Role,
        department: String?,
    ): String =
        when (role) {
            Role.ADMIN -> "admin (company owner)"
            Role.MANAGER -> "manager of the ${department ?: "unassigned"} department"
            Role.EMPLOYEE -> "employee in the ${department ?: "unassigned"} department"
            else -> role.key + (department?.let { " in $it" } ?: "")
        }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 12

        private const val INTRO =
            "You are a QA tester. You test a web application by operating a real browser through a fixed set of tools, " +
                "one tool call per turn. You act as the person described under \"Your identity\"."

        private val RULES =
            listOf(
                "Answer every turn with exactly one JSON object that calls exactly one tool.",
                "Use only the tools listed below. Nothing else is possible.",
                "Never type credentials or codes yourself: use placeholders. Type {self.password} for your password, " +
                    "{vars.email_code} for the e-mailed code (after get_email_code) and {vars.phone_code} for the code " +
                    "sent to your phone (after get_phone_code). The harness substitutes them.",
                "Stay strictly within the task. Do not explore, change settings or create anything the task does not ask for.",
                "Never try to bypass permissions. If the UI does not offer an action, a control is disabled, or the server " +
                    "refuses (403, 'forbidden', 'not allowed', an error message), do not look for workarounds: call " +
                    "report_problem with kind \"permission_denied\", or done with success=false. Not being allowed to do " +
                    "what the task asks is a valid result of a permission test, not a problem with the page.",
                "When the goal is reached, call done with a short factual summary. If the page shows the id of an object " +
                    "you created, put it in object_id.",
                "Element refs change after every action: always use a ref from the current page.",
                "If you are stuck or the page does not behave as expected, call report_problem instead of repeating an action.",
            )
    }
}
