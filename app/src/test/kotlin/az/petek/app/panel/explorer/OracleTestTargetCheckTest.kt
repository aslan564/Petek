/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel.explorer

import az.petek.explorer.domain.TestTargetVerdict
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.TestCompany
import az.petek.oracle.testing.FakeTargetOracle
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI

class OracleTestTargetCheckTest {
    private val site = URI("http://127.0.0.1:18080")
    private val owner = "owner@test.kadrohr.com"
    private val oracle =
        FakeTargetOracle().apply {
            companies["c1"] = TestCompany("c1", "Pətək Test MMC", "PTK-1", isTest = true)
            owners[owner] = "c1"
        }

    @Test
    fun `the company of the role sessions flagged is_test confirms the target`() =
        runTest {
            OracleTestTargetCheck(oracle, site, owner).check(URI("http://127.0.0.1:18080/announcements")) shouldBe
                TestTargetVerdict.Confirmed("company c1 is_test=true")
        }

    @Test
    fun `a company that is not flagged as test data is refused`() =
        runTest {
            oracle.companies["c1"] = TestCompany("c1", "Real MMC", "PTK-1", isTest = false)

            val verdict = OracleTestTargetCheck(oracle, site, owner).check(site)

            verdict.shouldBeInstanceOf<TestTargetVerdict.Refused>().reason shouldBe "şirkət c1 is_test deyil"
        }

    @Test
    fun `another site never gets the test token`() =
        runTest {
            val verdict = OracleTestTargetCheck(oracle, site, owner).check(URI("https://kadrohr.com"))

            verdict.shouldBeInstanceOf<TestTargetVerdict.Refused>().reason shouldContain "başqa sayta göndərilmir"
        }

    @Test
    fun `without a token, a company or an answer the target is refused without the owner's e-mail`() =
        runTest {
            OracleTestTargetCheck(FakeTargetOracle(isAvailable = false), site, owner)
                .check(site)
                .shouldBeInstanceOf<TestTargetVerdict.Refused>()
                .reason shouldContain "PETEK_TEST_TOKEN"
            OracleTestTargetCheck(oracle, site, null).check(site).shouldBeInstanceOf<TestTargetVerdict.Refused>()
            OracleTestTargetCheck(oracle, site, "nobody@test.kadrohr.com").check(site).shouldBeInstanceOf<TestTargetVerdict.Refused>()
            val broken =
                object : az.petek.oracle.domain.TargetOracle by oracle {
                    override suspend fun companyByOwner(ownerEmail: String) =
                        throw OracleException("company of owner $ownerEmail: HTTP 500")
                }
            OracleTestTargetCheck(broken, site, owner)
                .check(site)
                .shouldBeInstanceOf<TestTargetVerdict.Refused>()
                .reason shouldNotContain owner
        }
}
