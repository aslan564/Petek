/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel.runs

import az.petek.app.cli.TargetGuard
import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import java.net.URI

/** The container a run uses, and what to do when the run is over. */
internal class RunTarget(
    val container: AppContainer,
    private val release: () -> Unit,
) {
    /** Releases what was built for this run only (nothing for the panel's own container). */
    fun close() = release()
}

/**
 * Chooses the object graph a panel run uses. A run against the configured target (no "Hədəf sayt", or the same address
 * as `PETEK_TARGET`) uses the panel's own [main] container. A run against another address gets a container of its own
 * built by [derive] from the configuration with that address as `PETEK_TARGET` (the campaign's target, the test API and
 * everything else follow it, exactly as if `.env` named it), sharing the panel's database; it is closed when the run
 * ends. The caller has checked the address with the target policy.
 */
internal class RunTargets(
    private val main: AppContainer,
    private val derive: (PetekConfig) -> AppContainer,
) {
    fun lease(target: URI?): RunTarget {
        if (target == null || TargetGuard.origin(target) == TargetGuard.origin(main.config.target)) return RunTarget(main) {}
        val own = derive(main.config.copy(target = target))
        return RunTarget(own) { own.close() }
    }
}
