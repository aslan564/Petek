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

import az.petek.campaign.domain.ActorExpression
import az.petek.campaign.domain.ActorSelector
import az.petek.campaign.domain.Budget
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignSettings
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TargetProfile
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.dashboard.domain.RunPlanView
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.testing.InMemoryIdentityRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class PanelRunWatchTest {
    private val plans = CopyOnWriteArrayList<RunPlanView>()
    private val watch = PanelRunWatch { plans += it }
    private val runs = watch.wrap(InMemoryEvidence())
    private val identities = watch.wrap(InMemoryIdentityRepository())

    private val campaign =
        Campaign(
            settings =
                CampaignSettings(
                    target = URI("https://kadro.test"),
                    testers = 2,
                    seed = 1,
                    names = emptyList(),
                    roles = RoleQuota(1, 0, 1),
                    departments = listOf("IT"),
                    registration = RegistrationQuota(0, 1),
                    budget = Budget(5, 5),
                    onFail = OnFail.CONTINUE,
                    name = "watch",
                ),
            target = TargetProfile(emptyMap(), emptyMap(), emptyMap()),
            setup = emptyList(),
            steps =
                listOf(
                    ScenarioStep(
                        "look",
                        StepPhase.MAIN,
                        ActorExpression(listOf(ActorSelector(Role.EMPLOYEE)), "employee[*]"),
                        StepAction.Do("Bax"),
                        null,
                        null,
                        false,
                        emptyList(),
                        null,
                        1,
                    ),
                ),
            sourceHash = "abc",
        )

    private fun record(id: String) = RunRecord(RunId(id), RunTag("k7x2"), "abc", "watch", 1, "https://kadro.test", Instant.EPOCH, null)

    private fun identity(
        agent: String,
        role: Role,
    ) = Identity(AgentId(agent), agent, "$agent@t.test", Secret("password-123"), "+994500000000", role, "IT", RegistrationMode.COMPANY_CODE)

    @Test
    fun `the expected run's id completes the start and its plan is published when its identities are planned`() =
        runTest {
            val started = CompletableDeferred<RunId>()
            watch.expect(campaign, started)

            runs.create(record("run_7"))
            started.await() shouldBe RunId("run_7")
            plans.shouldBeEmpty()

            identities.replaceAll(
                RunId("run_7"),
                IdentityPlan(RunTag("k7x2"), listOf(identity("a02", Role.EMPLOYEE), identity("a01", Role.ADMIN))),
            )

            val plan = plans.single()
            plan.runId shouldBe RunId("run_7")
            plan.campaignName shouldBe "watch"
            plan.steps.single().agentIds shouldContainExactly listOf(AgentId("a02"))
        }

    @Test
    fun `runs nobody expects and identities of other runs publish nothing`() =
        runTest {
            runs.create(record("run_cli"))
            identities.replaceAll(RunId("run_cli"), IdentityPlan(RunTag("k7x2"), emptyList()))

            val started = CompletableDeferred<RunId>()
            watch.expect(campaign, started)
            runs.create(record("run_8"))
            identities.replaceAll(RunId("run_other"), IdentityPlan(RunTag("k7x2"), emptyList()))

            plans.shouldBeEmpty()
            started.await() shouldBe RunId("run_8")
        }

    @Test
    fun `a plan is published once and a failing listener never breaks the run`() =
        runTest {
            val failing = PanelRunWatch { error("the board is broken") }
            val store = InMemoryIdentityRepository()
            val started = CompletableDeferred<RunId>()
            failing.expect(campaign, started)
            failing.wrap(InMemoryEvidence()).create(record("run_9"))

            failing.wrap(store).replaceAll(RunId("run_9"), IdentityPlan(RunTag("k7x2"), listOf(identity("a01", Role.ADMIN))))

            store.findByRun(RunId("run_9")).size shouldBe 1
        }
}
