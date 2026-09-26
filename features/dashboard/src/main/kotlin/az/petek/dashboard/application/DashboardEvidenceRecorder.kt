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

import az.petek.dashboard.domain.DashboardUpdate
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.UsageRecord

/**
 * Decorator that shows recorded evidence on the [dashboard]: steps become the agent's last action and timeline lines,
 * SCREENSHOT artifacts the agent's latest picture, events, receipts, assertions and findings counters and lines.
 * Usage records are only forwarded.
 *
 * Every call goes to [delegate] first and its result (or exception) is the caller's, unchanged; the dashboard sees a
 * record only after the delegate stored it, so the board never shows evidence the store does not have. Feeding the
 * dashboard never throws and never waits ([LiveDashboard.submit]), so a dashboard problem cannot break recording.
 * Wrap it outermost so that it sees what every other decorator let through.
 */
class DashboardEvidenceRecorder(
    private val delegate: EvidenceRecorder,
    private val dashboard: LiveDashboard,
) : EvidenceRecorder {
    override suspend fun step(record: StepRecord) {
        delegate.step(record)
        dashboard.submit { DashboardUpdate.StepRecorded(record, it) }
    }

    override suspend fun artifact(record: ArtifactRecord) {
        delegate.artifact(record)
        dashboard.submit { DashboardUpdate.ArtifactRecorded(record, it) }
    }

    override suspend fun event(record: EventRecord) {
        delegate.event(record)
        dashboard.submit { DashboardUpdate.EventRecorded(record, it) }
    }

    override suspend fun receipt(record: EventReceipt) {
        delegate.receipt(record)
        dashboard.submit { DashboardUpdate.ReceiptRecorded(record, it) }
    }

    override suspend fun assertion(record: AssertionRecord) {
        delegate.assertion(record)
        dashboard.submit { DashboardUpdate.AssertionRecorded(record, it) }
    }

    override suspend fun finding(record: FindingRecord) {
        delegate.finding(record)
        dashboard.submit { DashboardUpdate.FindingRecorded(record, it) }
    }

    override suspend fun usage(record: UsageRecord) = delegate.usage(record)
}
