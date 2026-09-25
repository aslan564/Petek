package az.petek.agent.application

import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.DecisionProtocol
import az.petek.browser.domain.PageSnapshot
import az.petek.core.model.Role
import az.petek.identity.domain.Identity

/**
 * Builds the tester persona prompts. The system prompt depends only on the agent's identity and the run's roster,
 * so it is byte-identical for every turn of an agent and can be cached by the provider; everything that changes
 * per turn (task progress, history, page) goes into the user turn. Nothing here is time-dependent.
 *
 * Secrets never appear: the password is described as the `{self.password}` placeholder, and the user turn is
 * passed through [redact] because page snapshots and observations may echo what was typed.
 */
class PromptBuilder(
    private val protocol: DecisionProtocol,
    /** How many of the most recent turns are replayed to the model. */
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
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
            RULES.forEachIndexed { i, rule -> appendLine("${i + 1}. $rule") }
            appendLine()
            appendLine(protocol.describeTools())
            appendLine()
            appendIdentity(runtime.identity)
            appendLine()
            appendCompany(runtime)
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
        appendLine("- Role: ${roleDescription(identity)}")
        appendLine("- E-mail: ${identity.email}")
        appendLine("- Phone: ${identity.phone}")
        appendLine("- Password: secret and never shown to you; type {self.password} wherever it is needed.")
        appendLine("- Tester id: ${identity.agentId}")
    }

    private fun StringBuilder.appendCompany(runtime: AgentRuntime) {
        val roster = runtime.roster.ifEmpty { listOf(runtime.identity) }
        val departments = roster.mapNotNull { it.department }.distinct()
        appendLine("Company context:")
        appendLine("- You and your colleagues below work in the same company on the site under test; all accounts are test accounts.")
        if (departments.isNotEmpty()) appendLine("- Departments: ${departments.joinToString(", ")}")
        appendLine("- The company code, once known, can be typed as {shared.company_code}.")
        appendLine("- People (name - role - e-mail):")
        roster.forEach { person ->
            val you = if (person.agentId == runtime.identity.agentId) " (you)" else ""
            appendLine("  - ${person.displayName} - ${roleDescription(person)} - ${person.email}$you")
        }
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

    private fun roleDescription(identity: Identity): String =
        when (identity.role) {
            Role.ADMIN -> "admin (company owner)"
            Role.MANAGER -> "manager of the ${identity.department ?: "unassigned"} department"
            Role.EMPLOYEE -> "employee in the ${identity.department ?: "unassigned"} department"
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
                    "report_problem with kind \"permission_denied\", or done with success=false.",
                "When the goal is reached, call done with a short factual summary. If the page shows the id of an object " +
                    "you created, put it in object_id.",
                "Element refs change after every action: always use a ref from the current page.",
                "If you are stuck or the page does not behave as expected, call report_problem instead of repeating an action.",
            )
    }
}
