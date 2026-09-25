/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.config.PetekConfig
import az.petek.app.config.WebUrls
import az.petek.core.error.PetekException
import az.petek.core.security.TargetPolicy
import az.petek.core.security.TargetVerdict
import java.net.URI

/** A command refused to touch its target (production host, wrong target for a run). Exit code 2. */
class TargetRefusedException(
    message: String,
) : PetekException(message)

/** The checks every command runs before it contacts a target (CLAUDE.md rule 8). */
object TargetGuard {
    /**
     * Throws [TargetRefusedException] unless [policy] allows [target]. The policy judges the canonical spelling
     * ([WebUrls.canonical]): `https://KadroHR.com./` is the production host `kadrohr.com`.
     */
    fun requireAllowed(
        policy: TargetPolicy,
        target: URI,
    ) {
        val verdict = policy.verify(WebUrls.canonical(target))
        if (verdict is TargetVerdict.Refused) throw TargetRefusedException("Refusing to contact the target: ${verdict.reason}")
    }

    /**
     * Teardown deletes through the test API of the configured target, so it must be the target the run was made
     * against: company ids of one system mean nothing (or something else) on another.
     */
    fun requireRunTarget(
        runTarget: String,
        configured: URI,
    ) {
        val recorded =
            try {
                URI(runTarget)
            } catch (_: java.net.URISyntaxException) {
                null
            }
        if (recorded == null || origin(recorded) != origin(configured)) {
            throw TargetRefusedException(
                "The run was made against ${PetekConfig.masked(recorded ?: URI("about:unknown"))}, " +
                    "but PETEK_TARGET is ${PetekConfig.masked(configured)}; point PETEK_TARGET at the run's target to tear it down",
            )
        }
    }

    /** Scheme, host, effective port and path without a trailing slash; the parts that name one deployment. */
    fun origin(url: URI): String {
        val scheme = url.scheme?.lowercase().orEmpty()
        val port =
            when {
                url.port != -1 -> url.port
                scheme == "https" -> HTTPS_PORT
                scheme == "http" -> HTTP_PORT
                else -> -1
            }
        return "$scheme://${url.host?.lowercase().orEmpty()}:$port${url.rawPath.orEmpty().trimEnd('/')}"
    }

    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
}
