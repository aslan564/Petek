/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
 * The company roster in the system prompt is bounded by [rosterLimit], so the prompt of every agent stays the same
 * size whether the run has 30 testers or 500. When the run is larger, the people an agent most likely deals with
 * are listed (itself, the admin, its department's managers and colleagues, then the other managers) and the rest are
 * counted; the list itself is in agent-id order.
 */
class PromptBuilder(
    private val protocol: DecisionProtocol,
    /** How many of the most recent turns are replayed to the model. */
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
    /** How many people of the roster the system prompt lists at most. */
    private val rosterLimit: Int = DEFAULT_ROSTER_LIMIT,
    /** What summaries and reported problems are written in ([WorkingLanguage.AUTO]: the task's own language). */
    private val language: WorkingLanguage = WorkingLanguage.AUTO,
) {
    init {
        require(historyLimit >= 1) { "historyLimit must be at least 1, was $historyLimit" }
        require(rosterLimit >= 1) { "rosterLimit must be at least 1, was $rosterLimit" }
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

    private fun StringBuilder.appendCompany(runtime: AgentRuntime) {
        val roster = runtime.roster.ifEmpty { listOf(Colleague.of(runtime.identity)) }
        val departments = roster.mapNotNull { it.department }.distinct()
        appendLine("Company context:")
        appendLine("- You and your colleagues below work in the same company on the site under test; all accounts are test accounts.")
        if (departments.isNotEmpty()) appendLine("- Departments: ${departments.joinToString(", ")}")
        appendLine("- The company code, once known, can be typed as {shared.company_code}.")
        appendLine("- People (name - role - e-mail):")
        val listed = mostRelevant(roster, runtime.identity)
        listed.forEach { person ->
            val you = if (person.agentId == runtime.identity.agentId) " (you)" else ""
            appendLine("  - ${person.displayName} - ${roleDescription(person.role, person.department)} - ${person.email}$you")
        }
        val unlisted = roster.size - listed.size
        if (unlisted > 0) appendLine("  - … and $unlisted more colleagues, not listed here")
    }

    /** At most [rosterLimit] people, the ones [self] most likely deals with first, listed in agent-id order. */
    private fun mostRelevant(
        roster: List<Colleague>,
        self: Identity,
    ): List<Colleague> {
        if (roster.size <= rosterLimit) return roster
        val department = self.department

        fun rank(person: Colleague): Int =
            when {
                person.agentId == self.agentId -> 0
                person.role == Role.ADMIN -> 1
                department != null && person.department == department && person.role == Role.MANAGER -> 2
                department != null && person.department == department -> 3
                person.role == Role.MANAGER -> 4
                else -> 5
            }
        return roster
            .sortedWith(compareBy<Colleague>(::rank).thenBy { it.agentId })
            .take(rosterLimit)
            .sortedBy { it.agentId }
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

        /** Lists everyone of a 30-tester campaign; larger runs list the 40 most relevant people. */
        const val DEFAULT_ROSTER_LIMIT = 40

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
