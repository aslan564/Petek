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

package az.petek.agent.testing

import az.petek.agent.application.InMemorySharedRunState
import az.petek.agent.application.runs.RunFunctionSettings
import az.petek.agent.application.runs.RunFunctions
import az.petek.agent.domain.ActionOutcome
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.TargetProfile
import az.petek.core.testing.FakeHarnessClock
import az.petek.core.testing.SequentialIdGenerator
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.identity.domain.Identity
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.testing.FakeTargetOracle
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One agent, the simulated site and every collaborator of the standard run functions. With [contractSite] the agent's
 * browser is [SimulatedKadro] (docs/TARGET_CONTRACT.md); without it the plain [browser], which a test scripts itself
 * (see [ScriptedSite]) for sites described by a custom [target] profile.
 */
class RunFunctionFixture(
    val identity: Identity,
    oracleAvailable: Boolean = true,
    roster: List<Identity> = AgentTestData.roster,
    oracleOverride: ((FakeTargetOracle) -> TargetOracle)? = null,
    settings: RunFunctionSettings = RunFunctionSettings(),
    target: TargetProfile = TargetProfile.DEFAULT,
    contractSite: Boolean = true,
) {
    val clock = FakeHarnessClock()
    val verification = FakeVerification()
    val oracle = FakeTargetOracle(isAvailable = oracleAvailable)
    val browser = FakeBrowserSession(identity.agentId.value, clock)
    val site = SimulatedKadro(verification, oracle, browser)
    val shared = InMemorySharedRunState()
    val evidence = InMemoryEvidence()
    val artifacts = InMemoryArtifactStore()
    val runtime = AgentTestData.runtime(if (contractSite) site else browser, identity, roster, shared, target = target)
    val registry =
        RunFunctions.standard(
            oracle = oracleOverride?.invoke(oracle) ?: oracle,
            verification = verification,
            recorder = evidence,
            artifacts = artifacts,
            clock = clock,
            ids = SequentialIdGenerator(),
            settings = settings,
        )

    suspend fun run(
        name: String,
        args: Map<String, String> = emptyMap(),
        timeout: Duration = 30.minutes,
    ): ActionOutcome = registry[name]!!.execute(runtime, args, AgentTestData.step(scenarioStep = "setup-$name", timeout = timeout))

    /** RUN steps recorded so far, in order. */
    val steps: List<StepRecord> get() = evidence.stepList.filter { it.kind == StepKind.RUN }

    fun actions(function: String): List<String> = steps.map { it.action }.filter { it.startsWith("$function: ") }

    fun artifactsOf(type: ArtifactType) = evidence.artifactList.filter { it.type == type }

    /** The password was typed into the browser, but is nowhere in the evidence. */
    fun assertPasswordNotRecorded() {
        val password = identity.password.reveal()
        evidence.stepList.forEach { step ->
            listOfNotNull(step.action, step.detail, step.llmReason).forEach { it shouldNotContain password }
        }
    }

    fun storageStateSaved(): Boolean = browser.actions.any { it == "saveStorageState ${runtime.storageStatePath}" }
}
