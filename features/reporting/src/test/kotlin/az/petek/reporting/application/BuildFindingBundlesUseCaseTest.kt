/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.application

import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.reporting.ReportTestData
import az.petek.reporting.domain.RunNotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BuildFindingBundlesUseCaseTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a finding comes with its step and its evidence, text evidence inline and images by path`() =
        runBlocking<Unit> {
            val evidence = InMemoryEvidence()
            val artifacts = InMemoryArtifactStore(root)
            val run = ReportTestData.run()
            evidence.create(run)
            val step = ReportTestData.step("read_announce", "a17", stepId = "stp_read_announce_a17")
            evidence.step(step)
            val screenshot = artifacts.write(run.runId, step.stepId, "a17", ArtifactType.SCREENSHOT, byteArrayOf(1, 2, 3))
            val oracle = artifacts.write(run.runId, step.stepId, "a17", ArtifactType.ORACLE, "{\"status\":\"published\"}".toByteArray())
            listOf(screenshot, oracle).forEach { record ->
                Files.createDirectories(artifacts.resolve(record).parent)
                Files.write(artifacts.resolve(record), artifacts.contents.getValue(record.artifactId))
                evidence.artifact(record)
            }
            evidence.finding(
                ReportTestData.finding(
                    "fnd_1",
                    "read_announce",
                    "a17",
                    artifacts = listOf(screenshot.artifactId.value, oracle.artifactId.value),
                ),
            )
            evidence.finding(ReportTestData.finding("fnd_2", "join", "a07"))
            val useCase = BuildFindingBundlesUseCase(evidence, evidence, artifacts, Dispatchers.Unconfined)

            val bundle = useCase.bundles(run.runId, FindingId("fnd_1")).single()

            bundle.target shouldBe "https://staging.kadrohr.test"
            bundle.step?.stepId shouldBe StepId("stp_read_announce_a17")
            bundle.evidence.map { it.type } shouldBe listOf(ArtifactType.SCREENSHOT, ArtifactType.ORACLE)
            bundle.evidence[0].text shouldBe null
            bundle.evidence[0].path shouldBe artifacts.resolve(screenshot).toString()
            bundle.evidence[1].text shouldBe "{\"status\":\"published\"}"
            useCase.bundles(run.runId).map { it.finding.findingId.value } shouldBe listOf("fnd_1", "fnd_2")
            useCase.bundles(run.runId, FindingId("fnd_none")).shouldBeEmpty()
            shouldThrow<RunNotFoundException> { useCase.bundles(RunId("run_unknown")) }
        }
}
