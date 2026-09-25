/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
