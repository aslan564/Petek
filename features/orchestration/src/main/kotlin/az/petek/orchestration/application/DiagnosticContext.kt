/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Supplies extra coroutine context for the run and for each agent's work, so the composition root can attach a
 * logging context element (SLF4J MDC with `run_id` / `agent_id`, CLAUDE.md "Loglarda MDC") without this feature
 * depending on a logging backend. [agentId] is null for harness-level work.
 */
fun interface DiagnosticContext {
    fun of(
        runId: RunId,
        agentId: AgentId?,
    ): CoroutineContext

    companion object {
        val NONE: DiagnosticContext = DiagnosticContext { _, _ -> EmptyCoroutineContext }
    }
}
