/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.application.runs

import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.testing.AgentTestData
import az.petek.agent.testing.AgentTestData.sel
import az.petek.agent.testing.RunFunctionFixture
import az.petek.agent.testing.SimulatedKadro
import az.petek.evidence.domain.StepStatus
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.domain.TestCompany
import az.petek.oracle.testing.FakeTargetOracle
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RegisterOwnerRunFunctionTest {
    private val admin = AgentTestData.admin

    @Test
    fun `the owner signs up, passes both codes and publishes the new company`() =
        runTest {
            val fixture = RunFunctionFixture(admin)
            fixture.site.phoneVerification = true

            val outcome = fixture.run("register_owner", mapOf("company" to " Sınaq MMC "))

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.objectId shouldBe SimulatedKadro.COMPANY_ID
            outcome.summary shouldContain "as owner of 'Sınaq MMC'"
            fixture.site.signedIn?.name shouldBe admin.displayName
            fixture.oracle.companies[SimulatedKadro.COMPANY_ID]?.name shouldBe "Sınaq MMC"
            fixture.shared.get(SharedRunState.COMPANY_ID) shouldBe SimulatedKadro.COMPANY_ID
            fixture.shared.get(SharedRunState.COMPANY_CODE) shouldBe SimulatedKadro.COMPANY_CODE
            fixture.browser.actions shouldContain "fillSelector ${sel("verify.phone_code")}=${fixture.oracle.otps[admin.phone]}"
            fixture.storageStateSaved() shouldBe true
            fixture.assertPasswordNotRecorded()
        }

    @Test
    fun `the company name defaults to the MVP test company`() =
        runTest {
            val fixture = RunFunctionFixture(admin)

            fixture.run("register_owner").status shouldBe ActionStatus.SUCCEEDED

            fixture.oracle.companies[SimulatedKadro.COMPANY_ID]?.name shouldBe RegisterOwnerRunFunction.DEFAULT_COMPANY
            fixture.site.fields["register.company"] shouldBe "Pətək Test MMC"
        }

    @Test
    fun `without the test API the owner still signs up but publishes nothing`() =
        runTest {
            val fixture = RunFunctionFixture(admin, oracleAvailable = false)

            val outcome = fixture.run("register_owner")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.objectId.shouldBeNull()
            fixture.shared.get(SharedRunState.COMPANY_ID).shouldBeNull()
        }

    @Test
    fun `a failing company lookup does not undo a successful sign-up`() =
        runTest {
            val failing = { inner: FakeTargetOracle ->
                object : TargetOracle by inner {
                    override suspend fun companyByOwner(ownerEmail: String): TestCompany? =
                        throw OracleException("HTTP 500 from /test/companies")
                }
            }
            val fixture = RunFunctionFixture(admin, oracleOverride = failing)

            val outcome = fixture.run("register_owner")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.objectId.shouldBeNull()
            outcome.summary shouldContain "The company was not published: the test API failed (HTTP 500 from /test/companies)."
            fixture.shared.get(SharedRunState.COMPANY_ID).shouldBeNull()
            fixture.storageStateSaved() shouldBe true
            fixture.steps.single { it.action == "register_owner: look up the company owned by ${admin.email}" }.status shouldBe
                StepStatus.ERROR
        }

    @Test
    fun `a company the test API does not know yet is reported, not published`() =
        runTest {
            val fixture = RunFunctionFixture(admin)
            fixture.site.registerCompanyInOracle = false

            val outcome = fixture.run("register_owner")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldContain "the test API does not know it yet"
            fixture.steps.single { it.action == "register_owner: look up the company owned by ${admin.email}" }.status shouldBe
                StepStatus.FAILED
        }

    @Test
    fun `a phone step cannot be passed without the test API`() =
        runTest {
            val fixture = RunFunctionFixture(admin, oracleAvailable = false)
            fixture.site.phoneVerification = true

            val outcome = fixture.run("register_owner")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldContain "/test/otp"
        }

    @Test
    fun `a sign-up form that is not accepted fails the registration`() =
        runTest {
            val fixture = RunFunctionFixture(admin)
            fixture.site.acceptForms = false

            val outcome = fixture.run("register_owner")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.REGISTRATION_FAILED
            outcome.summary shouldContain "The sign-up form was not accepted (still on /register)"
        }

    @Test
    fun `only the owner identity may run the owner sign-up`() =
        runTest {
            val fixture = RunFunctionFixture(AgentTestData.itEmployee)

            val outcome = fixture.run("register_owner")

            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            fixture.browser.actions shouldBe emptyList()
        }
}
