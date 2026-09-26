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

package az.petek.core.security

import java.net.URI

/**
 * Guards against pointing test agents at production by accident (AGENTS.md rule 8).
 * A target whose host exactly matches an entry of [productionHosts] is refused unless [allowProduction] is set explicitly.
 * The refusal names the `.env` variables (`PETEK_PRODUCTION_HOSTS`, `PETEK_ALLOW_PRODUCTION`), so the user knows
 * exactly which switch decides it.
 */
data class TargetPolicy(
    val productionHosts: Set<String>,
    val allowProduction: Boolean,
) {
    fun verify(target: URI): TargetVerdict {
        val host = target.host?.lowercase() ?: return TargetVerdict.Refused("Target URL has no host: $target")
        if (target.scheme !in setOf("http", "https")) return TargetVerdict.Refused("Only http(s) targets are supported: $target")
        val isProduction = productionHosts.any { host == it.trim().lowercase() }
        return when {
            !isProduction -> {
                TargetVerdict.Allowed
            }

            allowProduction -> {
                TargetVerdict.Allowed
            }

            else -> {
                TargetVerdict.Refused(
                    "Target '$host' is a production host (listed in PETEK_PRODUCTION_HOSTS). Use a staging target, " +
                        "or set PETEK_ALLOW_PRODUCTION=true in .env to test it deliberately.",
                )
            }
        }
    }
}

sealed interface TargetVerdict {
    data object Allowed : TargetVerdict

    data class Refused(
        val reason: String,
    ) : TargetVerdict
}
