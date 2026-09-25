/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.application

import az.petek.core.ids.RunId
import az.petek.dashboard.domain.AgentProfile
import az.petek.dashboard.domain.DashboardUpdate
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityRepository

/**
 * Decorator that shows each planned identity's department and registration mode on the [dashboard]. Only the public
 * [AgentProfile] reaches the dashboard: e-mails, phones and passwords stay in the repository. Everything else is plain
 * delegation; the dashboard sees a plan only after [delegate] stored it, and feeding it never throws.
 */
class DashboardIdentityRepository(
    private val delegate: IdentityRepository,
    private val dashboard: LiveDashboard,
) : IdentityRepository by delegate {
    override suspend fun replaceAll(
        runId: RunId,
        plan: IdentityPlan,
    ) {
        delegate.replaceAll(runId, plan)
        dashboard.submit { DashboardUpdate.AgentsPlanned(runId, plan.identities.map(AgentProfile::of), it) }
    }
}
