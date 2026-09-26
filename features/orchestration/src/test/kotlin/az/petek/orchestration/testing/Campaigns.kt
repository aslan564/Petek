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

package az.petek.orchestration.testing

import az.petek.campaign.domain.ActorExpression
import az.petek.campaign.domain.ActorSelector
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Budget
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignSettings
import az.petek.campaign.domain.EmitSpec
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.WaitForSpec
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun selector(
    role: Role,
    department: String? = null,
    registration: RegistrationMode? = null,
    nth: Int? = null,
) = ActorSelector(role, department, registration, nth)

fun actors(vararg selectors: ActorSelector) = ActorExpression(selectors.toList(), selectors.joinToString(" | ") { it.toString() })

fun admin() = actors(selector(Role.ADMIN))

fun managers(department: String? = null) = actors(selector(Role.MANAGER, department))

fun employees(
    department: String? = null,
    nth: Int? = null,
) = actors(selector(Role.EMPLOYEE, department, nth = nth))

fun everyoneButAdmin() = actors(selector(Role.EMPLOYEE), selector(Role.MANAGER))

fun step(
    id: String,
    actors: ActorExpression,
    action: StepAction = StepAction.Do("do $id"),
    phase: StepPhase = StepPhase.MAIN,
    emits: String? = null,
    idSource: IdSource? = null,
    waitFor: String? = null,
    waitTimeout: Duration = 30.seconds,
    parallel: Boolean = false,
    assertions: List<AssertionSpec> = emptyList(),
    onFail: OnFail? = null,
) = ScenarioStep(
    id = id,
    phase = phase,
    actors = actors,
    action = action,
    emits = emits?.let { EmitSpec(it, idSource) },
    waitFor = waitFor?.let { WaitForSpec(it, waitTimeout) },
    parallel = parallel,
    assertions = assertions,
    onFail = onFail,
    line = 1,
)

fun setupStep(
    id: String,
    actors: ActorExpression,
    action: StepAction = StepAction.Run("register_and_login"),
) = step(id, actors, action, phase = StepPhase.SETUP)

fun campaign(
    setup: List<ScenarioStep> = emptyList(),
    steps: List<ScenarioStep> = emptyList(),
    managers: Int = 2,
    employees: Int = 4,
    departments: List<String> = listOf("IT", "HR"),
    onFail: OnFail = OnFail.CONTINUE,
    maxMinutes: Int = 30,
    maxStepsPerAgent: Int = 25,
    idSources: Map<String, IdSource> = emptyMap(),
): Campaign {
    val nonAdmins = managers + employees
    return Campaign(
        settings =
            CampaignSettings(
                target = URI("https://staging.example.test"),
                testers = 1 + nonAdmins,
                seed = 42,
                names = emptyList(),
                roles = RoleQuota(admin = 1, manager = managers, employee = employees),
                departments = departments,
                registration = RegistrationQuota(invite = (nonAdmins + 1) / 2, companyCode = nonAdmins / 2),
                budget = Budget(maxStepsPerAgent = maxStepsPerAgent, maxMinutes = maxMinutes),
                onFail = onFail,
                name = "test-campaign",
            ),
        target = TargetProfile(emptyMap(), emptyMap(), idSources),
        setup = setup,
        steps = steps,
        sourceHash = "hash-1",
    )
}
