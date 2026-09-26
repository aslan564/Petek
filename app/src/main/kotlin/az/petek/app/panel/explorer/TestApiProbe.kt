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

import az.petek.oracle.domain.TargetOracle
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Whether the target really has the test API of docs/TARGET_CONTRACT.md, asked before anything is written for the
 * explorer: a configured token alone does not make a site a test system (a production site without test mode would
 * take a sign-up for real). Returns the reason in Azerbaijani when it does not, null when it does.
 */
internal fun interface TestApiProbe {
    suspend fun refusal(): String?
}

/**
 * Asks the test API for the company of an owner that cannot exist: a test API answers with JSON (404 for "no such
 * company"), a site without one answers with its pages, and a wrong token with 401. Nothing is written.
 */
internal class OracleTestApiProbe(
    private val oracle: TargetOracle,
    private val mailDomain: String,
) : TestApiProbe {
    override suspend fun refusal(): String? {
        val owner = URLEncoder.encode("petek-probe@$mailDomain", Charsets.UTF_8)
        val answer =
            try {
                oracle.get("/test/companies?owner=$owner")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return "hədəfin test API-si cavab vermədi (${e::class.simpleName}); test şirkəti yaradılmadı."
            }
        return when {
            answer.status == UNAUTHORIZED -> "hədəfin test API-si PETEK_TEST_TOKEN-i qəbul etmədi; test şirkəti yaradılmadı."
            answer.status in TEST_API_ANSWERS && answer.body is JsonObject -> null
            else -> "hədəfdə test API-si yoxdur (docs/TARGET_CONTRACT.md); test şirkəti yaradılmadı, heç nə yazılmadı."
        }
    }

    private companion object {
        const val UNAUTHORIZED = 401
        val TEST_API_ANSWERS = setOf(200, 404)
    }
}
