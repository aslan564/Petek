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
