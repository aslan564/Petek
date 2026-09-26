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

package az.petek.dashboard.application

import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.testing.FakeHarnessClock
import az.petek.dashboard.testing.FailingClock
import az.petek.dashboard.testing.Records.RUN
import az.petek.dashboard.testing.Records.identity
import az.petek.dashboard.testing.Records.runRecord
import az.petek.evidence.domain.RunResult
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.testing.InMemoryIdentityRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DashboardRepositoriesTest {
    @Test
    fun `a created run fills the header after the run repository stored it`() =
        runBlocking<Unit> {
            val store = InMemoryEvidence()
            val dashboard = LiveDashboard(FakeHarnessClock())
            val runs = DashboardRunRepository(store, dashboard)

            runs.create(runRecord(name = "Elan axını"))
            runs.finish(RUN, RunResult.PASSED, runRecord().startedAt.plusSeconds(5))

            store.runList shouldHaveSize 1
            store.runList.single().result shouldBe RunResult.PASSED
            runs.find(RUN).shouldNotBeNull()
            dashboard.snapshot().run.campaignName shouldBe "Elan axını"
        }

    @Test
    fun `planned identities show only their public profile`() =
        runBlocking<Unit> {
            val store = InMemoryIdentityRepository()
            val dashboard = LiveDashboard(FakeHarnessClock())
            val identities = DashboardIdentityRepository(store, dashboard)
            val planned = listOf(identity(1), identity(2, role = Role.MANAGER, department = "IT"))

            identities.replaceAll(RUN, IdentityPlan(RunTag("k7x2"), planned))

            identities.findByRun(RUN) shouldHaveSize 2
            val view = dashboard.snapshot()
            view.agents[1].role shouldBe Role.MANAGER
            view.agents[1].department shouldBe "IT"
            view.agents[1].registration shouldBe RegistrationMode.INVITE
            val shown = view.toString()
            planned.forEach {
                shown shouldNotContain it.email
                shown shouldNotContain it.phone
                shown shouldNotContain it.password.reveal()
            }
        }

    @Test
    fun `a broken dashboard never breaks the repositories`() =
        runBlocking<Unit> {
            val clock = FailingClock(FakeHarnessClock())
            val dashboard = LiveDashboard(clock)
            val evidence = InMemoryEvidence()
            val identityStore = InMemoryIdentityRepository()
            clock.failing = true

            DashboardRunRepository(evidence, dashboard).create(runRecord())
            DashboardIdentityRepository(identityStore, dashboard).replaceAll(RUN, IdentityPlan(RunTag("k7x2"), listOf(identity(1))))

            evidence.runList shouldHaveSize 1
            identityStore.findByRun(RUN) shouldHaveSize 1
        }
}
