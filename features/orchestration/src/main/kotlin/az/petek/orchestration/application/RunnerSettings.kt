/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import java.nio.file.Path

/**
 * Environment-dependent settings of [DefaultCampaignRunner] (they come from `.env`, not from the campaign).
 *
 * @property mailDomain catch-all test domain used for every generated e-mail, e.g. `test.kadrohr.com`.
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
) {
    init {
        require(mailDomain.isNotBlank()) { "mailDomain must not be blank" }
    }

    companion object {
        val DEFAULT_ACTIVATING_RUN_FUNCTIONS: Set<String> = setOf("register_owner", "register_and_login", "login")
    }
}
