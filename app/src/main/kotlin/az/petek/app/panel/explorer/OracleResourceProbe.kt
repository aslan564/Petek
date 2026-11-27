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

import az.petek.explorer.domain.TestApiProbe
import az.petek.oracle.domain.TargetOracle
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * [TestApiProbe] on the target's test API (Faza 25.2): `GET /test/<resource>/latest?by=<e-mail>` must answer 2xx with
 * the object carrying the trial touch's marker. A 404 means the test API does not give that object (it does not serve
 * the resource, or not by this user): no oracle check is written for it. Anything else (a 5xx, an unreadable
 * answer, no test API) says nothing, so the probe answers null and the draft stays without the check too.
 */
internal class OracleResourceProbe(
    private val oracle: TargetOracle,
) : TestApiProbe {
    override suspend fun serves(
        resource: String,
        by: String,
        marker: String,
    ): Boolean? {
        if (!oracle.isAvailable || !SAFE_RESOURCE.matches(resource)) return null
        val response = oracle.get("/test/$resource/latest?by=" + URLEncoder.encode(by, StandardCharsets.UTF_8))
        return when (response.status) {
            in SUCCESS -> marker in response.rawBody
            NOT_FOUND -> false
            else -> null
        }
    }

    private companion object {
        val SUCCESS = 200..299
        const val NOT_FOUND = 404

        /** A resource name is one path segment of the explorer's own slugs: nothing that could leave `/test/`. */
        val SAFE_RESOURCE = Regex("[a-z0-9][a-z0-9_-]{0,62}")
    }
}
