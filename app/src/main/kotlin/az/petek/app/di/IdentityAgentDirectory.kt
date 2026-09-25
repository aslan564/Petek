/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.di

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.identity.domain.IdentityRepository
import az.petek.reporting.domain.AgentDirectory

/** Gives the report the display names of a run's testers (reporting cannot see the identity feature itself). */
class IdentityAgentDirectory(
    private val identities: IdentityRepository,
) : AgentDirectory {
    override suspend fun names(runId: RunId): Map<AgentId, String> = identities.findByRun(runId).associate { it.agentId to it.displayName }
}
