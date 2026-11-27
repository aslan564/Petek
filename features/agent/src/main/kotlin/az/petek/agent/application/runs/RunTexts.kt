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

package az.petek.agent.application.runs

import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.SharedRunState
import az.petek.browser.domain.RunText

/**
 * The run's own texts a page may show a tester (`site_health`'s `look`): the tester's name, e-mail and phone, the
 * company code once published, and every colleague's name and e-mail. Another run writes other ones, so where they show
 * is not compared between releases. Never a password: the identity's is not read, and a [az.petek.agent.domain.Colleague]
 * has none. The texts only go to the browser that looks for them: they are never logged, stored or given to the LLM
 * ([RunText] prints its kind only).
 */
internal object RunTexts {
    const val TESTER_NAME = "tester_name"
    const val TESTER_EMAIL = "tester_email"
    const val TESTER_PHONE = "tester_phone"
    const val COMPANY_CODE = "company_code"
    const val COLLEAGUE_NAME = "colleague_name"
    const val COLLEAGUE_EMAIL = "colleague_email"

    /** Shorter texts would match parts of ordinary words. */
    const val MIN_CHARS = 3

    /** The most texts one look searches the page for. */
    const val MAX_TEXTS = 200

    /** The tester's own texts first, then the company code, then the colleagues' in roster order. */
    fun of(runtime: AgentRuntime): List<RunText> {
        val self = runtime.identity
        val own =
            listOf(
                RunText(TESTER_NAME, self.displayName),
                RunText(TESTER_EMAIL, self.email),
                RunText(TESTER_PHONE, self.phone),
            )
        val company = listOfNotNull(runtime.shared.get(SharedRunState.COMPANY_CODE)?.let { RunText(COMPANY_CODE, it) })
        val colleagues =
            runtime.roster
                .filter { it.agentId != self.agentId }
                .flatMap { listOf(RunText(COLLEAGUE_NAME, it.displayName), RunText(COLLEAGUE_EMAIL, it.email)) }
        return (own + company + colleagues)
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.length >= MIN_CHARS }
            .distinctBy { it.text.lowercase() }
            .take(MAX_TEXTS)
    }
}
