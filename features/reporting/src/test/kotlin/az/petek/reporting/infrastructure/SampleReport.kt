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

package az.petek.reporting.infrastructure

import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.RunResult
import az.petek.reporting.ReportTestData
import az.petek.reporting.domain.FailedAgentRow
import az.petek.reporting.domain.LatencyStats
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportSummary
import az.petek.reporting.domain.StabilityRow
import az.petek.reporting.domain.StepRow

/** A report model with a value in every section, built directly so writer tests do not depend on the use cases. */
object SampleReport {
    const val SCRIPT = "<script>alert('x')</script>"

    fun model(
        stability: List<StabilityRow>? =
            listOf(StabilityRow("announce", runs = 3, passed = 3), StabilityRow("read_announce", runs = 3, passed = 2)),
        stepDetail: String? = null,
        note: String = "The target (C) confirms the change but the receiver (B) did not see it: delivery or UI error.",
        agentName: String = "Əli Məmmədov",
        campaignName: String = "portal-core",
    ) = ReportModel(
        run = ReportTestData.run(result = RunResult.FAILED, repeatGroup = "grp_1", repeatIndex = 2, campaignName = campaignName),
        summary =
            ReportSummary(
                stepsPassed = 57,
                stepsFailed = 3,
                assertionsPassed = 88,
                assertionsFailed = 4,
                assertionsSkipped = 2,
                agents = 30,
                durationMs = 245_000,
                inputTokens = 1_234_567,
                outputTokens = 89_012,
                costUsd = 0.042,
                realtimeTransports = listOf("SSE", "POLLING"),
                cacheReadTokens = 1_600,
            ),
        steps =
            listOf(
                StepRow("announce", "a01", agentName, "DO", "PASSED", 4_200, stepDetail, "art_1"),
                StepRow("join", "a07", "Vəli Həsənov", "RUN", "FAILED", 61_000, "mail_timeout: no e-mail", null),
                StepRow("read_announce", "a17", null, "WAIT", "BLOCKED", 120_000, null, "art_evil"),
            ),
        assertions = emptyList(),
        latency =
            listOf(
                LatencyStats(
                    event = "announcement_created #42",
                    receivers = 29,
                    received = 28,
                    missing = listOf("a17"),
                    avgMs = 812,
                    p95Ms = 1450,
                    maxMs = 2210,
                    perReceiverMs = mapOf("a02" to 640L, "a17" to null),
                ),
            ),
        findings =
            listOf(
                ReportTestData.finding(
                    "fnd_1",
                    "read_announce",
                    "a17",
                    FindingClass.DELIVERY_UI,
                    a = null,
                    b = "Sabah 10:00 ümumi iclas -> (none)",
                    c = "published -> published",
                    note = note,
                    artifacts = listOf("art_1", "art_2", "art_evil"),
                ),
                ReportTestData.finding(
                    "fnd_2",
                    "join",
                    "a07",
                    FindingClass.BACKEND,
                    a = "run register_and_login",
                    b = "mail_timeout: no e-mail",
                    c = "no e-mail sent",
                    note = "no e-mail sent (mail_timeout).",
                ),
            ),
        failedAgents = listOf(FailedAgentRow("a07", "Vəli Həsənov", "join", "mail_timeout")),
        stability = stability,
        artifactLinks =
            mapOf(
                "art_1" to "../a01/0001-screenshot.png",
                "art_2" to "../a17/0002-oracle.json",
                "art_evil" to "javascript:alert(1)",
            ),
        usage =
            listOf(
                ReportTestData.usage("a02", input = 234_567, output = 9_012, cost = 0.002, cacheRead = 100, calls = 4),
                ReportTestData.usage("a01", input = 1_000_000, output = 80_000, cost = 0.04, cacheRead = 1_500, calls = 12),
            ),
    )
}
