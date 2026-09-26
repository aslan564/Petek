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

package az.petek.app.logging

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class MdcContextTest {
    private val runId = RunId("run_42")

    @AfterEach
    fun clear() = MDC.clear()

    @Test
    fun `a run context carries the run id and no agent id`() =
        runBlocking<Unit> {
            withContext(MdcDiagnosticContext.of(runId, null)) {
                MDC.get("run_id") shouldBe "run_42"
                MDC.get("agent_id").shouldBeNull()
            }
        }

    @Test
    fun `each agent's coroutine sees its own agent id across thread switches`() =
        runBlocking<Unit> {
            val seen =
                withContext(MdcDiagnosticContext.of(runId, null)) {
                    (1..8)
                        .map { index ->
                            async(Dispatchers.Default + MdcDiagnosticContext.of(runId, AgentId.of(index))) {
                                repeat(20) { yield() }
                                MDC.get("run_id") to MDC.get("agent_id")
                            }
                        }.awaitAll()
                }

            seen shouldBe (1..8).map { "run_42" to AgentId.of(it).value }
        }

    @Test
    fun `the thread's previous context comes back afterwards`() =
        runBlocking<Unit> {
            MDC.put("request", "outer")
            MDC.put("agent_id", "a99")

            withContext(MdcDiagnosticContext.of(runId, null)) {
                MDC.get("request") shouldBe "outer"
                MDC.get("agent_id").shouldBeNull()
            }

            MDC.get("request") shouldBe "outer"
            MDC.get("agent_id") shouldBe "a99"
            MDC.get("run_id").shouldBeNull()
        }
}
