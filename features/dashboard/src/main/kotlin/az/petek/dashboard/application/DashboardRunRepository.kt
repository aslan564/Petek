/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.application

import az.petek.dashboard.domain.DashboardUpdate
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository

/**
 * Decorator that tells the [dashboard] about every run the runner creates (campaign name, target, start time), so the
 * header is filled before the first agent moves and a `--repeat` series moves the board to each new run. Everything
 * else is plain delegation; the dashboard learns about a run only after [delegate] stored it, and feeding it never
 * throws.
 */
class DashboardRunRepository(
    private val delegate: RunRepository,
    private val dashboard: LiveDashboard,
) : RunRepository by delegate {
    override suspend fun create(run: RunRecord) {
        delegate.create(run)
        dashboard.submit { DashboardUpdate.RunCreated(run, it) }
    }
}
