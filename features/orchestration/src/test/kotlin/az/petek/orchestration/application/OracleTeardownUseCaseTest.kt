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

package az.petek.orchestration.application

import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResource
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.domain.TestCompany
import az.petek.oracle.testing.FakeTargetOracle
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

class OracleTeardownUseCaseTest {
    private val runs = InMemoryEvidence()
    private val oracle = FakeTargetOracle()
    private val teardown = OracleTeardownUseCase(runs, oracle)
    private val start = Instant.parse("2026-01-01T10:00:00Z")

    private suspend fun run(
        id: String,
        startedAt: Instant = start,
        vararg companies: String,
    ): RunId {
        val runId = RunId(id)
        runs.create(RunRecord(runId, RunTag("abcd"), "hash", "campaign", 42, "https://staging.example.test", startedAt))
        companies.forEach { runs.addResource(RunResource(runId, "company", it, startedAt)) }
        return runId
    }

    private fun testCompany(id: String) {
        oracle.companies[id] = TestCompany(id, "Pətək Test MMC", "PTK", isTest = true)
    }

    @Test
    fun `the companies of the run are deleted and unregistered`() =
        runTest {
            testCompany("c1")
            val runId = run("run_1", companies = arrayOf("c1"))

            val result = teardown.teardown(runId)

            result shouldBe TeardownResult(runId, listOf("company:c1"), emptyList())
            oracle.deleted shouldContainExactly listOf("c1")
            runs.resources(runId).shouldBeEmpty()
        }

    @Test
    fun `without a run id the latest run is torn down`() =
        runTest {
            testCompany("old")
            testCompany("new")
            run("run_old", start, "old")
            val latest = run("run_new", start.plusSeconds(60), "new")

            val result = teardown.teardown(null)

            result.runId shouldBe latest
            oracle.deleted shouldContainExactly listOf("new")
        }

    @Test
    fun `nothing to do when no run exists`() =
        runTest {
            teardown.teardown(null) shouldBe TeardownResult(null, emptyList(), emptyList())
        }

    @Test
    fun `a company that is not a test company is refused and stays registered`() =
        runTest {
            oracle.companies["prod"] = TestCompany("prod", "Real", null, isTest = false)
            val runId = run("run_1", companies = arrayOf("prod"))

            val result = teardown.teardown(runId)

            result.removed.shouldBeEmpty()
            result.failures.single() shouldContain "company:prod: Company prod is not a test company"
            runs.resources(runId).map { it.externalId } shouldBe listOf("prod")
        }

    @Test
    fun `a company that is already gone counts as removed`() =
        runTest {
            val runId = run("run_1", companies = arrayOf("gone"))

            val result = teardown.teardown(runId)

            result.removed shouldBe listOf("company:gone")
            result.failures.shouldBeEmpty()
            runs.resources(runId).shouldBeEmpty()
        }

    @Test
    fun `tearing down twice is harmless`() =
        runTest {
            testCompany("c1")
            val runId = run("run_1", companies = arrayOf("c1"))

            teardown.teardown(runId)
            val second = teardown.teardown(runId)

            second shouldBe TeardownResult(runId, emptyList(), emptyList())
            oracle.deleted shouldContainExactly listOf("c1")
        }

    @Test
    fun `one failure does not stop the other resources`() =
        runTest {
            testCompany("c2")
            oracle.companies["prod"] = TestCompany("prod", "Real", null, isTest = false)
            val runId = run("run_1", companies = arrayOf("prod", "c2"))

            val result = teardown.teardown(runId)

            result.removed shouldBe listOf("company:c2")
            result.failures.size shouldBe 1
        }

    @Test
    fun `resources of an unknown kind are reported, not guessed`() =
        runTest {
            val runId = run("run_1")
            runs.addResource(RunResource(runId, "mailbox", "m1", start))

            val result = teardown.teardown(runId)

            result.failures.single() shouldContain "no teardown is known for resource kind 'mailbox'"
        }

    @Test
    fun `without a test API nothing is deleted and the reason is reported`() =
        runTest {
            val offline = OracleTeardownUseCase(runs, FakeTargetOracle(isAvailable = false))
            val runId = run("run_1", companies = arrayOf("c1"))

            val result = offline.teardown(runId)

            result.failures.single() shouldContain "test API is not available"
            runs.resources(runId).map { it.externalId } shouldBe listOf("c1")
        }

    @Test
    fun `an oracle call that times out internally is reported, not mistaken for a cancelled teardown`() =
        runTest {
            testCompany("c1")
            testCompany("c2")
            val slow =
                object : TargetOracle by oracle {
                    override suspend fun deleteCompany(companyId: String) {
                        if (companyId == "c1") throw CancellationException("DELETE /test/companies/c1 timed out")
                        oracle.deleteCompany(companyId)
                    }
                }
            val runId = run("run_1", companies = arrayOf("c1", "c2"))

            val result = OracleTeardownUseCase(runs, slow).teardown(runId)

            result.failures.single() shouldContain "company:c1: DELETE /test/companies/c1 timed out"
            result.removed shouldBe listOf("company:c2")
            runs.resources(runId).map { it.externalId } shouldBe listOf("c1")
        }

    @Test
    fun `an oracle error is reported when the company still exists`() =
        runTest {
            testCompany("c1")
            val flaky =
                object : TargetOracle by oracle {
                    override suspend fun deleteCompany(companyId: String): Unit = throw OracleException("HTTP 503 from /test/companies/c1")
                }
            val runId = run("run_1", companies = arrayOf("c1"))

            val result = OracleTeardownUseCase(runs, flaky).teardown(runId)

            result.failures.single() shouldContain "HTTP 503"
            runs.resources(runId).map { it.externalId } shouldBe listOf("c1")
        }
}
