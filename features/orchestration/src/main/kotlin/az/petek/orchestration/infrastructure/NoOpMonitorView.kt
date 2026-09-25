/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.infrastructure

import az.petek.core.ids.RunId
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunSummary

/** Monitor that ignores everything (tests, `--quiet`). Stateless, so a single instance is shared. */
object NoOpMonitorView : MonitorView {
    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) = Unit

    override fun agentUpdated(status: AgentStatus) = Unit

    override fun stepStarted(scenarioStep: String) = Unit

    override fun message(text: String) = Unit

    override fun runFinished(summary: RunSummary) = Unit
}
