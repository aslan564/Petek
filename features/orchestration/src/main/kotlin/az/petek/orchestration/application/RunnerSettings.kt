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

package az.petek.orchestration.application

import az.petek.browser.domain.BrowserProxy
import az.petek.identity.domain.GivenAccount
import java.net.URI
import java.nio.file.Path

/**
 * Environment-dependent settings of [DefaultCampaignRunner] (they come from `.env`, not from the campaign).
 *
 * @property mailDomain catch-all test domain used for every generated e-mail, e.g. `test.portal.example`.
 * @property mailbox the owner's own inbox; when set every tester gets its `+` address instead (Faza 16).
 * @property storageRoot directory for per-agent browser storage state: `<storageRoot>/<runId>/<agentId>.json`.
 * @property activatingRunFunctions `run` functions that log an agent in; a successful setup step running one of them
 *   (or any successful setup `do` step, which is how a UI sign-up is written) marks the identity ACTIVE.
 */
data class RunnerSettings(
    val mailDomain: String,
    val storageRoot: Path,
    val mailbox: String? = null,
    /** Send `X-Petek-Correlation-Id` with every tester request (`PETEK_CORRELATION_HEADER`, Faza 14). */
    val correlationHeader: Boolean = false,
    val activatingRunFunctions: Set<String> = DEFAULT_ACTIVATING_RUN_FUNCTIONS,
    /** The owner's accounts for a target (its profile's `accounts`); `login` testers sign in with them (Faza 18). */
    val accounts: (URI) -> List<GivenAccount> = { emptyList() },
    /**
     * One proxy per live tester (`PETEK_PROXIES`, Faza 21): tester *n* of a wave goes out through proxy *n*. When there
     * are fewer proxies than live testers the run does not start and says so. Empty: every tester shares the machine's IP.
     */
    val proxies: List<BrowserProxy> = emptyList(),
) {
    init {
        require(mailDomain.isNotBlank()) { "mailDomain must not be blank" }
    }

    companion object {
        val DEFAULT_ACTIVATING_RUN_FUNCTIONS: Set<String> = setOf("register_owner", "register_and_login", "login")
    }
}
