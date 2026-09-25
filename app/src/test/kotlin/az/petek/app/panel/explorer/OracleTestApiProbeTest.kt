/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
    private val path = "/test/companies?owner=petek-probe%40test.kadrohr.com"

    private fun probe(oracle: TargetOracle) = OracleTestApiProbe(oracle, "test.kadrohr.com")

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
