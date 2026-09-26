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

package az.petek.app.panel.runs

import az.petek.app.cli.TargetGuard
import az.petek.app.config.PetekConfig
import az.petek.app.config.TargetProfileConfig
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
        // A site with a target profile brings its own test API, token, production hosts and mail (Faza 10).
        val own = derive(TargetProfileConfig.forTarget(main.config, target))
        return RunTarget(own) { own.close() }
    }
}
