/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.StepId
import az.petek.core.testing.SequentialIdGenerator
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.EvidenceSource.HARNESS
import az.petek.evidence.domain.EvidenceSource.ORACLE
import az.petek.evidence.domain.EvidenceSource.RECEIVER
import az.petek.evidence.domain.EvidenceSource.SENDER
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict.FAILED
import az.petek.evidence.domain.Verdict.PASSED
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.reporting.ReportTestData.RUN_ID
import az.petek.reporting.ReportTestData.assertion
import az.petek.reporting.ReportTestData.event
import az.petek.reporting.ReportTestData.receipt
import az.petek.reporting.ReportTestData.run
import az.petek.reporting.ReportTestData.step
import az.petek.reporting.ReportTestData.usage
import az.petek.reporting.application.BuildReportUseCase
import az.petek.reporting.application.FinalizeRunUseCase
import az.petek.reporting.domain.AgentDirectory
import az.petek.reporting.domain.ThreeSourceJudge
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The whole reporting feature as the app assembles it: evidence in, judged findings and both report files out. */
class ReportPipelineTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a finished run becomes a judged report whose links open the evidence files`() =
        runTest {
            val evidence = InMemoryEvidence()
            val store = InMemoryArtifactStore(root)

            suspend fun screenshot(
                stepId: String,
                owner: String,
            ) = store.write(RUN_ID, StepId(stepId), owner, ArtifactType.SCREENSHOT, byteArrayOf(1, 2, 3)).also {
                // The in-memory store keeps bytes in memory; put them where a file store would.
                Files.createDirectories(store.resolve(it).parent)
                Files.write(store.resolve(it), byteArrayOf(1, 2, 3))
                evidence.artifact(it)
            }

            evidence.create(run())
            evidence.step(step("announce", "a01", StepStatus.PASSED, StepKind.DO, stepId = "stp_announce"))
            evidence.step(step("read_announce", "a02", StepStatus.PASSED, StepKind.WAIT, stepId = "stp_wait_a02"))
            evidence.step(step("join", "a07", StepStatus.FAILED, StepKind.RUN, detail = "mail_timeout: 60s", stepId = "stp_join_a07"))
            val sender = screenshot("stp_announce", "a01")
            val receiver = screenshot("stp_wait_a02", "a02")
            screenshot("stp_join_a07", "a07")
            evidence.assertion(assertion("announce", "a01", SENDER, PASSED, artifacts = listOf(sender.artifactId.value)))
            evidence.assertion(assertion("announce", "a01", ORACLE, PASSED))
            evidence.assertion(
                assertion(
                    "read_announce",
                    "a02",
                    RECEIVER,
                    FAILED,
                    "Sabah 10:00 ümumi iclas",
                    null,
                    artifacts = listOf(receiver.artifactId.value),
                ),
            )
            evidence.assertion(assertion("read_announce", "a02", HARNESS, FAILED, "<= 5000 ms", "7400 ms"))
            evidence.assertion(assertion("read_announce", "a02", ORACLE, PASSED, "receipt of a02", "receipt of a02"))
            evidence.event(event("evt_1"))
            evidence.receipt(receipt("evt_1", "a02", 7_400))
            evidence.usage(usage("a01", 12_000, 800, 0.0123))
            val builder = BuildReportUseCase(evidence, evidence, store, AgentDirectory { mapOf(AgentId("a02") to "Sahil Quliyev") })
            val finalize =
                FinalizeRunUseCase(
                    runs = evidence,
                    query = evidence,
                    recorder = evidence,
                    artifacts = store,
                    judge = ThreeSourceJudge(SequentialIdGenerator()),
                    builder = builder,
                    writers = listOf(MarkdownReportWriter(), HtmlReportWriter()),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val directory = finalize.finalize(RUN_ID)

            directory shouldBe root.resolve("run_test/report")
            val md = Files.readString(directory.resolve("report.md"))
            val html = Files.readString(directory.resolve("index.html"))
            md shouldContain "### 1. Çatdırılma / UI xətası · read_announce · Sahil Quliyev (a02)"
            md shouldContain "### 2. Backend xətası · join · a07"
            md shouldContain "| announcement_created \\#42 | 1 | 1 | 7400 ms | 7400 ms | 7400 ms | — |"
            md shouldContain "| Xərc | $0.0123 |"
            html shouldContain "Sahil Quliyev (a02)"
            html shouldContain "<img alt=\"screenshot 0002-screenshot.png\" src=\"../a02/0002-screenshot.png\""
            // Every link in the report resolves to an evidence file next to it.
            Regex("""(?:href|src)="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toSet().forEach { link ->
                directory.resolve(link).normalize().shouldExist()
            }
        }
}
