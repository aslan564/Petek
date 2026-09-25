/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.core.testing

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.EventId
import az.petek.core.ids.FindingId
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import java.util.concurrent.atomic.AtomicInteger

/** Predictable ids for tests: `run_1`, `stp_1`, `stp_2`, ... */
class SequentialIdGenerator : IdGenerator {
    private val counter = AtomicInteger(0)

    private fun next(prefix: String) = "${prefix}_${counter.incrementAndGet()}"

    override fun runId() = RunId(next("run"))

    override fun stepId() = StepId(next("stp"))

    override fun eventId() = EventId(next("evt"))

    override fun correlationId() = CorrelationId(next("cor"))

    override fun artifactId() = ArtifactId(next("art"))

    override fun findingId() = FindingId(next("fnd"))
}
