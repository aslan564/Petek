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

package az.petek.campaign.domain

import az.petek.core.model.RegistrationMode

/**
 * A visitor run (ADR-0012, amended 2026-09-27): a campaign that only looks at a site the way a few visitors would, as
 * the explorer's anonymous crawl does, and so may start on a site whose owner has not proved it is theirs. Every other
 * run writes (sign-ups, forms, the test API) and needs that proof.
 *
 * - every tester is a visitor: no companies (`tenant: none`) and the gate `guest` for all of them;
 * - at most [MAX_TESTERS] testers, so a visitor run never becomes a load on someone else's site;
 * - no `do` step (an AI agent may click and type) and every `run` step one of [READ_ONLY_FUNCTIONS]:
 *   `register_and_login` only opens the home page for a visitor, `site_health` reads pages, links, the console and the
 *   layout;
 * - no check that needs the site's test API or writes: no `oracle`, no `only_one_succeeds`, `http_status` only with
 *   GET, and no created object read through the test API.
 */
object VisitorRun {
    const val MAX_TESTERS = 3

    /** The run functions that only read the site. */
    val READ_ONLY_FUNCTIONS: Set<String> = setOf("register_and_login", "site_health")

    /** Why [campaign] is not a visitor run; empty when it is one. */
    fun problems(campaign: Campaign): List<String> {
        val settings = campaign.settings
        val problems = mutableListOf<String>()
        if (settings.tenant != Tenant.NONE) problems += "it has companies (tenant: company)"
        if (settings.registration.count(RegistrationMode.GUEST) != settings.testers) {
            problems += "not every tester is a visitor (registration: {guest: ${settings.testers}})"
        }
        if (settings.testers > MAX_TESTERS) problems += "it asks for ${settings.testers} testers, a visitor run takes at most $MAX_TESTERS"
        campaign.allSteps.forEach { step -> problems += stepProblems(step) }
        return problems
    }

    private fun stepProblems(step: ScenarioStep): List<String> {
        val problems = mutableListOf<String>()
        val action = step.action
        if (action is StepAction.Do) problems += "step '${step.id}' is a `do` step, where an AI agent may click and type"
        if (action is StepAction.Run && action.function !in READ_ONLY_FUNCTIONS) {
            problems += "step '${step.id}' runs `${action.function}`, which does not only read"
        }
        step.assertions.forEach { assertion ->
            val writesOrAsksTheTestApi =
                when (assertion) {
                    is AssertionSpec.Oracle, is AssertionSpec.OnlyOneSucceeds -> true
                    is AssertionSpec.HttpStatus -> !assertion.method.equals("GET", ignoreCase = true)
                    is AssertionSpec.VisibleText, is AssertionSpec.NotVisible, is AssertionSpec.Count, is AssertionSpec.LatencyMax -> false
                }
            if (writesOrAsksTheTestApi) {
                problems +=
                    "step '${step.id}' checks `${assertion.type}`, which writes or needs the site's test API"
            }
        }
        if (step.emits?.idSource is IdSource.OracleField) problems += "step '${step.id}' reads a created object through the site's test API"
        return problems
    }
}
