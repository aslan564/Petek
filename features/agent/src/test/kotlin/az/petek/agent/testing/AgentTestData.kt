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
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.AgentVariables
import az.petek.agent.domain.Colleague
import az.petek.agent.domain.SharedRunState
import az.petek.agent.domain.StepContext
import az.petek.browser.domain.BrowserSession
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.TemplateContext
import az.petek.core.ids.AgentId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.RunId
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.identity.domain.Identity
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Builders for the data every agent test needs. Values are fixed so assertions can name them. */
object AgentTestData {
    val RUN_ID = RunId("run_test")
    val RUN_STARTED_AT: Instant = Instant.parse("2026-01-01T09:59:00Z")
    val CORRELATION_ID = CorrelationId("cor_test")

    fun identity(
        index: Int,
        role: Role = Role.EMPLOYEE,
        department: String? = "IT",
        registration: RegistrationMode = RegistrationMode.INVITE,
        name: String = "Tester $index",
        password: String = "Pw-$index-Secret-9x!",
    ): Identity {
        val agentId = AgentId.of(index)
        return Identity(
            agentId = agentId,
            displayName = name,
            email = "tester.k7x2.$agentId@test.portal.example",
            password = Secret(password),
            phone = "+99450" + (1000000 + index),
            role = role,
            department = department,
            registration = registration,
        )
    }

    val admin: Identity = identity(1, Role.ADMIN, null, RegistrationMode.OWNER, name = "Əli Kərimov")
    val itManager: Identity = identity(2, Role.MANAGER, "IT", RegistrationMode.INVITE, name = "Vəli Həsənov")
    val hrManager: Identity = identity(3, Role.MANAGER, "HR", RegistrationMode.COMPANY_CODE, name = "Sahil Quliyev")
    val itEmployee: Identity = identity(4, Role.EMPLOYEE, "IT", RegistrationMode.COMPANY_CODE, name = "Cəmil Əliyev")
    val salesEmployee: Identity = identity(5, Role.EMPLOYEE, "Satış", RegistrationMode.INVITE, name = "Amil Məmmədov")

    val roster: List<Identity> = listOf(admin, itManager, hrManager, itEmployee, salesEmployee)

    fun runtime(
        session: BrowserSession,
        identity: Identity = itEmployee,
        roster: List<Identity> = AgentTestData.roster,
        shared: SharedRunState = InMemorySharedRunState(),
        variables: AgentVariables = AgentVariables(),
        target: TargetProfile = TargetProfile.DEFAULT,
    ) = AgentRuntime(
        runId = RUN_ID,
        identity = identity,
        roster = roster.map(Colleague::of),
        session = session,
        target = target,
        variables = variables,
        shared = shared,
        runStartedAt = RUN_STARTED_AT,
        storageStatePath = Path.of("build", "test-state", "${identity.agentId}.json"),
    )

    fun step(
        scenarioStep: String = "announce",
        maxSteps: Int = 20,
        timeout: Duration = 10.minutes,
    ) = StepContext(
        scenarioStep = scenarioStep,
        correlationId = CORRELATION_ID,
        templates = TemplateContext(lastId = null, self = emptyMap(), eventIds = emptyMap()),
        maxSteps = maxSteps,
        timeout = timeout,
    )

    /** `[data-testid="…"]` selector of a target profile key, as the run functions address it. */
    fun sel(key: String): String = TargetProfile.DEFAULT.selector(key)
}
