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

package az.petek.agent.application.runs

import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.testing.AgentTestData
import az.petek.agent.testing.RunFunctionFixture
import az.petek.agent.testing.SimulatedPortal
import az.petek.evidence.domain.StepStatus
import az.petek.oracle.domain.Invitee
import az.petek.oracle.domain.OracleSafetyException
import az.petek.oracle.domain.SeedCompanyRequest
import az.petek.oracle.domain.SeedCompanyResult
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.domain.TestCompany
import az.petek.oracle.testing.FakeTargetOracle
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SeedCompanyRunFunctionTest {
    private val admin = AgentTestData.admin

    /** A test API that also returns invitation links, as a real site may. */
    private class LinkingOracle(
        private val inner: FakeTargetOracle,
    ) : TargetOracle by inner {
        override suspend fun seedCompany(request: SeedCompanyRequest): SeedCompanyResult =
            inner.seedCompany(request).copy(inviteLinks = request.invites.associate { it.email to "https://x/invite/${it.name.length}" })
    }

    private fun FakeTargetOracle.ownedCompany(
        code: String? = SimulatedPortal.COMPANY_CODE,
        isTest: Boolean = true,
    ) {
        companies["c1"] = TestCompany("c1", "Pətək Test MMC", code, isTest)
        owners[admin.email.lowercase()] = "c1"
        owners[admin.email] = "c1"
    }

    @Test
    fun `the company is seeded with the roster's departments and invitations`() =
        runTest {
            val fixture = RunFunctionFixture(admin)
            fixture.oracle.ownedCompany()

            val outcome = fixture.run("seed_company")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.objectId shouldBe "c1"
            fixture.oracle.seeded.single() shouldBe
                SeedCompanyRequest(
                    companyId = "c1",
                    departments = listOf("IT", "HR", "Satış"),
                    invites =
                        listOf(
                            Invitee(AgentTestData.itManager.email, "Vəli Həsənov", "manager", "IT"),
                            Invitee(AgentTestData.salesEmployee.email, "Amil Məmmədov", "employee", "Satış"),
                        ),
                )
            fixture.shared.get(SharedRunState.COMPANY_ID) shouldBe "c1"
            fixture.shared.get(SharedRunState.COMPANY_CODE) shouldBe SimulatedPortal.COMPANY_CODE
            outcome.summary shouldBe "Seeded company c1: 3 departments, 2 invitations (0 links returned), company code PTK-4821."
        }

    @Test
    fun `invitation links returned by the test API are published per e-mail`() =
        runTest {
            val fixture = RunFunctionFixture(admin, oracleOverride = { LinkingOracle(it) })
            fixture.oracle.ownedCompany()

            fixture.run("seed_company").status shouldBe ActionStatus.SUCCEEDED

            fixture.shared.get(SharedRunState.inviteLink(AgentTestData.itManager.email)) shouldBe "https://x/invite/12"
            fixture.shared.get(SharedRunState.inviteLink(AgentTestData.salesEmployee.email.uppercase())) shouldBe "https://x/invite/13"
            fixture.shared.get(SharedRunState.inviteLink(AgentTestData.hrManager.email)).shouldBeNull()
        }

    @Test
    fun `a company that shows up in the test API a moment later is still found`() =
        runTest {
            val fixture = RunFunctionFixture(admin)
            launch {
                delay(3.seconds)
                fixture.oracle.ownedCompany()
            }

            fixture.run("seed_company").status shouldBe ActionStatus.SUCCEEDED
            currentTime shouldBe 3_000
        }

    @Test
    fun `without a company owned by the admin the step fails with a missing prerequisite`() =
        runTest {
            val fixture = RunFunctionFixture(admin)

            val outcome = fixture.run("seed_company")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldContain "knows no company owned by ${admin.email}"
            currentTime shouldBe 4_000
            fixture.oracle.seeded.shouldBeEmpty()
            fixture.steps.single { it.action == "seed_company: look up the company owned by ${admin.email}" }.status shouldBe
                StepStatus.FAILED
        }

    @Test
    fun `a test API that refuses to seed is a missing prerequisite, not an unexpected error`() =
        runTest {
            val refusing = { inner: FakeTargetOracle ->
                object : TargetOracle by inner {
                    override suspend fun seedCompany(request: SeedCompanyRequest): SeedCompanyResult =
                        throw OracleSafetyException("company ${request.companyId} is not a test company")
                }
            }
            val fixture = RunFunctionFixture(admin, oracleOverride = refusing)
            fixture.oracle.ownedCompany()

            val outcome = fixture.run("seed_company")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldBe "Test API refused: company c1 is not a test company"
            fixture.shared.get(SharedRunState.COMPANY_ID).shouldBeNull()
        }

    @Test
    fun `a company that is not flagged as test is never seeded`() =
        runTest {
            val fixture = RunFunctionFixture(admin)
            fixture.oracle.ownedCompany(isTest = false)

            val outcome = fixture.run("seed_company")

            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldContain "not flagged is_test"
            fixture.oracle.seeded.shouldBeEmpty()
        }

    @Test
    fun `a company code missing from the test API is read from the company page`() =
        runTest {
            val fixture = RunFunctionFixture(admin)
            fixture.oracle.ownedCompany(code = null)
            fixture.site.existingAccount(admin)
            fixture.run("login")

            fixture.run("seed_company").status shouldBe ActionStatus.SUCCEEDED

            fixture.shared.get(SharedRunState.COMPANY_CODE) shouldBe SimulatedPortal.COMPANY_CODE
        }

    @Test
    fun `an unknown company code fails when company-code testers depend on it`() =
        runTest {
            val fixture = RunFunctionFixture(admin)
            fixture.oracle.ownedCompany(code = null)

            val outcome = fixture.run("seed_company")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.objectId shouldBe "c1"
            outcome.summary shouldContain "2 company-code testers cannot join without it"
        }

    @Test
    fun `without the test API the code is published but invitations are impossible`() =
        runTest {
            val fixture = RunFunctionFixture(admin, oracleAvailable = false)
            fixture.site.existingAccount(admin)
            fixture.run("login")

            val outcome = fixture.run("seed_company")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldContain "2 invitation-mode testers cannot be invited; company code PTK-4821 was published"
            fixture.shared.get(SharedRunState.COMPANY_CODE) shouldBe SimulatedPortal.COMPANY_CODE
        }

    @Test
    fun `without the test API a company-code-only roster is fully served`() =
        runTest {
            val roster = listOf(admin, AgentTestData.hrManager, AgentTestData.itEmployee)
            val fixture = RunFunctionFixture(admin, oracleAvailable = false, roster = roster)
            fixture.site.existingAccount(admin)
            fixture.run("login")

            val outcome = fixture.run("seed_company")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldContain "published company code PTK-4821"
        }

    @Test
    fun `without the test API and without a visible code the step fails`() =
        runTest {
            val roster = listOf(admin, AgentTestData.hrManager)
            val fixture = RunFunctionFixture(admin, oracleAvailable = false, roster = roster)

            val outcome = fixture.run("seed_company")

            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldContain "company code is not shown on the company page; 1 company-code testers cannot join"
            fixture.shared.get(SharedRunState.COMPANY_CODE).shouldBeNull()
        }

    @Test
    fun `a missing code does not matter when nobody joins by company code`() =
        runTest {
            val fixture = RunFunctionFixture(admin, oracleAvailable = false, roster = listOf(admin))

            val outcome = fixture.run("seed_company")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldContain "nobody needs to join"
        }
}
