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

package az.petek.app.panel.explorer

import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.OracleResponse
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.testing.FakeTargetOracle
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OracleTestApiProbeTest {
    private val path = "/test/companies?owner=petek-probe%40test.portal.example"

    private fun probe(oracle: TargetOracle) = OracleTestApiProbe(oracle, "test.portal.example")

    @Test
    fun `a JSON answer of the test API, also 'no such company', means the target has one`() =
        runTest {
            probe(FakeTargetOracle().apply { respond(path, """{"error":"not_found"}""", status = 404) }).refusal().shouldBeNull()
            probe(FakeTargetOracle().apply { respond(path, """{"id":"c9","is_test":true}""") }).refusal().shouldBeNull()
        }

    @Test
    fun `a site answering with its pages, a refused token or no answer has no usable test API`() =
        runTest {
            probe(FakeTargetOracle()).refusal().shouldNotBeNull() shouldContain "test API-si yoxdur"
            val page = FakeTargetOracle().apply { responses[path] = OracleResponse(200, null, "<!doctype html><div id=root>") }
            probe(page).refusal().shouldNotBeNull() shouldContain "test API-si yoxdur"
            val refused = FakeTargetOracle().apply { respond(path, """{"error":"invalid_test_token"}""", status = 401) }
            probe(refused).refusal().shouldNotBeNull() shouldContain "PETEK_TEST_TOKEN"
            val down =
                object : TargetOracle by FakeTargetOracle() {
                    override suspend fun get(path: String): OracleResponse = throw OracleException("connection refused")
                }
            probe(down).refusal().shouldNotBeNull() shouldContain "cavab vermədi"
        }
}
