/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.core.security

import java.net.URI

/**
 * Guards against pointing test agents at production by accident (CLAUDE.md rule 8).
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
