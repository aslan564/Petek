/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.testing.CliHarness
import az.petek.app.testing.FakeTargets
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResource
import az.petek.faketarget.FakeTargetServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class TeardownCommandTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var target: FakeTargetServer

    @BeforeEach
    fun start() {
        target = FakeTargetServer().start()
    }

    @AfterEach
    fun stop() = target.close()

    private fun cli(token: String? = "dev-token"): CliHarness {
        val environment = mutableMapOf("PETEK_TARGET" to target.baseUrl.toString())
        token?.let { environment["PETEK_TEST_TOKEN"] = it }
        return CliHarness(dir, environment)
    }

    private suspend fun recordRun(
        cli: CliHarness,
        id: String,
        companyId: String?,
        runTarget: String = target.baseUrl.toString(),
        startedAt: Instant = Instant.parse("2026-09-01T10:00:00Z"),
    ): RunId {
        val runId = RunId(id)
        cli.evidence { stores ->
            stores.evidence.create(RunRecord(runId, RunTag("ab12"), "hash", "tiny", 7, runTarget, startedAt))
            companyId?.let { stores.evidence.addResource(RunResource(runId, "company", it, startedAt)) }
        }
        return runId
    }

    @Test
    fun `the latest run's test company is deleted on the target`() =
        runBlocking<Unit> {
            val cli = cli()
            val companyId = FakeTargets.registerOwner(target, "owner@test.kadrohr.com")
            val runId = recordRun(cli, "run_1", companyId)

            val result = cli.run("teardown")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Run $runId: removed company:$companyId"
            target.store.company(companyId) shouldBe null
            cli.evidence { it.evidence.resources(runId) }.shouldBeEmpty()
        }

    @Test
    fun `a second teardown finds nothing left`() =
        runBlocking<Unit> {
            val cli = cli()
            val companyId = FakeTargets.registerOwner(target, "owner@test.kadrohr.com")
            recordRun(cli, "run_1", companyId)
            cli.run("teardown").statusCode shouldBe 0

            val again = cli.run("teardown")

            again.statusCode shouldBe 0
            again.stdout shouldContain "nothing left to tear down"

            val asJson = cli.run("--json", "teardown")
            asJson.statusCode shouldBe 0
            asJson.stdout.trim() shouldBe """{"runId":"run_1","removed":[],"failures":[]}"""
        }

    @Test
    fun `a named run is torn down instead of the latest`() =
        runBlocking<Unit> {
            val cli = cli()
            val older = FakeTargets.registerOwner(target, "older@test.kadrohr.com")
            val newer = FakeTargets.registerOwner(target, "newer@test.kadrohr.com")
            recordRun(cli, "run_old", older, startedAt = Instant.parse("2026-09-01T10:00:00Z"))
            recordRun(cli, "run_new", newer, startedAt = Instant.parse("2026-09-02T10:00:00Z"))

            cli.run("teardown", "--run", "run_old").statusCode shouldBe 0

            target.store.company(older) shouldBe null
            target.store.company(newer)?.id shouldBe newer
        }

    @Test
    fun `a company that is not flagged as test data is never deleted`() =
        runBlocking<Unit> {
            val cli = cli()
            val realCompany = FakeTargets.registerOwner(target, "boss@real-customer.az")
            recordRun(cli, "run_1", realCompany)

            val result = cli.run("teardown")

            result.statusCode shouldBe 1
            result.stderr shouldContain "could not remove company:$realCompany"
            result.stderr shouldContain "is_test"
            target.store.company(realCompany)?.id shouldBe realCompany
            cli.evidence { it.evidence.resources(RunId("run_1")) } shouldHaveSize 1
        }

    @Test
    fun `without a test token nothing can be removed`() =
        runBlocking<Unit> {
            val cli = cli(token = null)
            recordRun(cli, "run_1", "c1")

            val result = cli.run("teardown")

            result.statusCode shouldBe 1
            result.stderr shouldContain "PETEK_TEST_TOKEN"
        }

    @Test
    fun `a run made against another target is refused`() =
        runBlocking<Unit> {
            val cli = cli()
            recordRun(cli, "run_1", "c1", runTarget = "https://staging.other.test")

            val result = cli.run("teardown")

            result.statusCode shouldBe 2
            result.stderr shouldContain "The run was made against https://staging.other.test"
        }

    @Test
    fun `nothing to do without any recorded run`() =
        runBlocking<Unit> {
            val result = cli().run("teardown")

            result.statusCode shouldBe 0
            result.stdout shouldContain "nothing to tear down"
        }

    @Test
    fun `an unknown run id exits with 1`() =
        runBlocking<Unit> {
            val result = cli().run("teardown", "--run", "run_missing")

            result.statusCode shouldBe 1
            result.stderr shouldContain "Run 'run_missing' not found"
        }
}
