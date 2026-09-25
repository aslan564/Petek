/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.application.runs

import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState

/**
 * Renders the templates of one flow execution right before their step (see [az.petek.campaign.domain.Flow]):
 * `{self.<field>}` from the agent's identity, `{shared.<key>}` from the run, `{vars.<key>}` from the agent's own values,
 * `{campaign.company}` and, in failure messages, `{url}`. Substituted values are inserted verbatim and never rendered
 * again, so a value containing braces cannot inject a placeholder.
 *
 * A shared value that is not published yet is awaited up to [RunFunctionSettings.companyCodeTimeout], recorded as
 * `wait for the company code`: testers read what the admin publishes while the admin may still be working on it. A
 * missing own value, a missing identity field or an unknown placeholder fails the flow with `missing_prerequisite`
 * (the validator rejects unknown placeholders before a run, so these are campaign mistakes caught late).
 */
internal class FlowTemplates(
    private val trace: RunTrace,
    private val flowName: String,
    private val company: String,
    private val settings: RunFunctionSettings,
) {
    private val runtime get() = trace.runtime

    suspend fun render(
        template: String,
        allowUrl: Boolean = false,
    ): String {
        if ('{' !in template) return template
        val rendered = StringBuilder()
        var from = 0
        PLACEHOLDER.findAll(template).forEach { match ->
            rendered.append(template, from, match.range.first).append(resolve(match.groupValues[1], allowUrl))
            from = match.range.last + 1
        }
        return rendered.append(template, from, template.length).toString()
    }

    private suspend fun resolve(
        name: String,
        allowUrl: Boolean,
    ): String {
        val namespace = name.substringBefore('.', "")
        val key = name.substringAfter('.', "")
        return when {
            namespace == "self" -> self(key)
            namespace == "shared" -> shared(key)
            namespace == "vars" -> runtime.variables[key] ?: missing("{vars.$key} is not set: no earlier step of this tester stored it")
            name == CAMPAIGN_COMPANY -> company
            name == URL && allowUrl -> trace.currentUrl()
            else -> missing("unknown placeholder {$name}")
        }
    }

    private fun self(field: String): String {
        val identity = runtime.identity
        return when (field) {
            "email" -> identity.email
            "password" -> identity.password.reveal()
            "name" -> identity.displayName
            "first_name" -> firstName(identity.displayName)
            "last_name" -> lastName(identity.displayName)
            "phone" -> identity.phone
            "agent_id" -> identity.agentId.value
            "role" -> identity.role.key
            "department" -> identity.department ?: missing("{self.department} has no value: ${identity.agentId} has no department")
            else -> missing("unknown placeholder {self.$field}")
        }
    }

    private suspend fun shared(key: String): String {
        val sharedKey = if (key == INVITE_LINK) SharedRunState.inviteLink(runtime.identity.email) else key
        runtime.shared.get(sharedKey)?.let { return it }
        val label = key.replace('_', ' ')
        val timeout = settings.companyCodeTimeout
        return trace.lookup("wait for the $label") { runtime.shared.await(sharedKey, timeout) }
            ?: throw RunFailure(FailureReason.MISSING_PREREQUISITE, "No $label was published within $timeout${publisherHint(key)}")
    }

    private fun missing(problem: String): Nothing = throw RunFailure(FailureReason.MISSING_PREREQUISITE, "Flow '$flowName': $problem.")

    companion object {
        /** The placeholder that types the tester's password; a step using it is shown as `***` in the evidence. */
        const val PASSWORD = "{self.password}"

        private const val CAMPAIGN_COMPANY = "campaign.company"
        private const val URL = "url"

        /** `{shared.invite_link}`: the invitation `seed_company` published for this tester's own e-mail. */
        private const val INVITE_LINK = "invite_link"

        private val PLACEHOLDER = Regex("""\{([a-z_][a-z0-9_.]*)}""")
        private val WHITESPACE = Regex("\\s+")

        /** Values `seed_company` (or the owner's sign-up) publishes for everyone else. */
        private val SEEDED = setOf(SharedRunState.COMPANY_CODE, SharedRunState.COMPANY_ID, INVITE_LINK)

        /** The display name's first word: what sign-up forms call the first name. */
        fun firstName(displayName: String): String = displayName.trim().split(WHITESPACE).first()

        /**
         * Everything after the first word (`Vüqar oğlu Məmmədov`, `Məmmədov II`), so first and last name together show
         * the display name again; a one-word name is its own last name, since forms require one.
         */
        fun lastName(displayName: String): String {
            val words = displayName.trim().split(WHITESPACE)
            return if (words.size > 1) words.drop(1).joinToString(" ") else words.first()
        }

        private fun publisherHint(key: String): String =
            if (key in SEEDED) "; did seed_company run?" else "; a set_shared or read step of an earlier flow must publish it."
    }
}
