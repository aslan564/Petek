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

package az.petek.app.testing

import az.petek.core.ids.RunId
import az.petek.dashboard.domain.DashboardSnapshot
import az.petek.dashboard.domain.ExplorationStatus
import az.petek.dashboard.domain.ExplorationView
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.RunPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Waits for the panel's background work in tests, by the panel's own live views (no sleeps). */
internal object PanelWaits {
    val TIMEOUT: Duration = 30.seconds

    /** The scripted site most panel tests explore: a home page linking a sign-in and a join form. */
    fun site(base: URI = URI("http://127.0.0.1:9")): FakeSiteEngine =
        FakeSiteEngine(base).apply {
            page("/", "Portal") {
                link("Daxil ol", "/login")
                link("Qoşul", "/join")
            }
            page("/login", "Daxil ol") {
                form("/login") {
                    field("E-poçt", "email", "email", "login-email")
                    field("Parol", "password", "password", "login-password")
                    submit("Daxil ol", "login-submit")
                }
            }
            page("/join", "Qoşul") {
                form("/join") {
                    field("Şirkət kodu", "code", testId = "join-company-code")
                    submit("Qoşul", "join-submit")
                }
            }
        }

    suspend fun PanelHarness.exploration(predicate: (ExplorationView) -> Boolean): ExplorationView =
        withTimeout(TIMEOUT) { backend.explorationUpdates.first(predicate) }

    /** Starts an exploration and returns its view once it ended, whether or not it produced a draft. */
    suspend fun PanelHarness.ended(instructions: PanelInstructions): ExplorationView {
        backend.startExploration(instructions)
        return exploration { it.id != "hazırlanır" && it.status != ExplorationStatus.RUNNING }
    }

    /** Starts an exploration and returns its view once it ended and its draft preview is on the screen. */
    suspend fun PanelHarness.explored(instructions: PanelInstructions = PanelHarness.instructions(site.base.toString())): ExplorationView {
        backend.startExploration(instructions)
        return exploration { it.id != "hazırlanır" && it.status != ExplorationStatus.RUNNING && it.draftYaml != null }
    }

    /** The board once [runId] ended (its report is written by then). */
    suspend fun PanelHarness.ended(runId: RunId): DashboardSnapshot =
        withTimeout(TIMEOUT) { panel.dashboard.updates.first { it.run.runId == runId && it.run.phase == RunPhase.FINISHED } }
}
