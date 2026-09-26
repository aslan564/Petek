/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.diagnostics

import az.petek.app.config.PetekConfig
import az.petek.core.error.PetekException
import java.net.URI

/** What one look at the site under test found: it answers, or the exact reason it does not. */
sealed interface TargetAnswer {
    data object Reachable : TargetAnswer

    data class Unreachable(
        val reason: String,
    ) : TargetAnswer
}

/**
 * The look at the site under test that every run and exploration takes before a single browser session opens
 * (CLAUDE.md rule 12, the owner's decision of 2026-09-26): the site that was given is contacted, and when it does
 * not answer nothing is tested and the reason is reported as it is. Pətək never substitutes another page, a
 * stand-in or an invented result for a site that is down, blocked or wrong.
 */
fun interface TargetReachability {
    suspend fun check(target: URI): TargetAnswer

    /** Throws [TargetUnreachableException] when [target] does not answer. */
    suspend fun require(target: URI) {
        val answer = check(target)
        if (answer is TargetAnswer.Unreachable) throw TargetUnreachableException(target, answer.reason)
    }

    companion object {
        /** For tests whose target is never contacted (a fake browser plays the site). */
        val ALWAYS: TargetReachability = TargetReachability { TargetAnswer.Reachable }
    }
}

/**
 * One anonymous GET of the target with [HttpProbe]: any HTTP answer below 500 (a page, a redirect to `/login`, a
 * 404 on the root) means the site is there; a server error or no answer at all (DNS, firewall, TLS, timeout) means
 * it is not, with the client's own error as the reason.
 */
class HttpTargetReachability(
    private val http: HttpProbe,
) : TargetReachability {
    override suspend fun check(target: URI): TargetAnswer =
        when (val answer = http.get(target)) {
            is HttpCheck.Answered -> {
                if (answer.status < SERVER_ERROR) TargetAnswer.Reachable else TargetAnswer.Unreachable("$answer")
            }

            is HttpCheck.Unreachable -> {
                TargetAnswer.Unreachable(answer.error)
            }
        }

    private companion object {
        const val SERVER_ERROR = 500
    }
}

/** The site under test does not answer, so nothing was tested. Exit code 2 on the command line. */
class TargetUnreachableException(
    val target: URI,
    val reason: String,
) : PetekException(
        "The site under test does not answer, so nothing was tested: ${PetekConfig.masked(target)} ($reason). " +
            "Check the address, the network and the site, then try again.",
    )
