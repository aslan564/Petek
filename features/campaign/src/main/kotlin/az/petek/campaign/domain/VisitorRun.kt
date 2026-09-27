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
 * A visitor run (ADR-0012, amended 2026-09-27): a campaign that only looks at a site the way visitors would, as the
 * explorer's anonymous crawl does, and so may start on a site whose owner has not proved it is theirs. Every other
 * run writes (sign-ups, forms, the test API) and needs that proof.
 *
 * - every tester is a visitor: no companies (`tenant: none`) and the gate `guest` for all of them;
 * - no `do` step (an AI agent may click and type) and every `run` step one of [READ_ONLY_FUNCTIONS]:
 *   `register_and_login` only opens the home page for a visitor, `site_health` reads pages, links, the console and the
 *   layout, `page_checks` reads titles, headings, images and links and asks other sites' links once;
 * - no check that needs the site's test API or writes: no `oracle`, no `only_one_succeeds`, `http_status` only with
 *   GET, and no created object read through the test API.
 *
 * Any number of testers may take part, all of them at once: the owner chooses how many, and only the machine's
 * capacity and the AI plan bound it, never the site (the owner's decision, 2026-09-27).
 */
object VisitorRun {
    /** The run functions that only read the site. */
    val READ_ONLY_FUNCTIONS: Set<String> = setOf("register_and_login", "site_health", "page_checks")

    /** Why a campaign is not a visitor run; [text] says it in English, callers may say it in the owner's language. */
    sealed interface Problem {
        val text: String

        data object Companies : Problem {
            override val text = "it has companies (tenant: company)"
        }

        data class NotAllVisitors(
            val testers: Int,
        ) : Problem {
            override val text = "not every tester is a visitor (registration: {guest: $testers})"
        }

        data class AiStep(
            val step: String,
        ) : Problem {
            override val text = "step '$step' is a `do` step, where an AI agent may click and type"
        }

        data class WritingFunction(
            val step: String,
            val function: String,
        ) : Problem {
            override val text = "step '$step' runs `$function`, which does not only read"
        }

        data class WritingCheck(
            val step: String,
            val check: String,
        ) : Problem {
            override val text = "step '$step' checks `$check`, which writes or needs the site's test API"
        }

        data class CreatedObject(
            val step: String,
        ) : Problem {
            override val text = "step '$step' reads a created object through the site's test API"
        }
    }

    /** Why [campaign] is not a visitor run, in English; empty when it is one. */
    fun problems(campaign: Campaign): List<String> = findProblems(campaign).map { it.text }

    /** Why [campaign] is not a visitor run; empty when it is one. */
    fun findProblems(campaign: Campaign): List<Problem> {
        val settings = campaign.settings
        val problems = mutableListOf<Problem>()
        if (settings.tenant != Tenant.NONE) problems += Problem.Companies
        if (settings.registration.count(RegistrationMode.GUEST) != settings.testers) problems += Problem.NotAllVisitors(settings.testers)
        campaign.allSteps.forEach { step -> problems += stepProblems(step) }
        return problems
    }

    private fun stepProblems(step: ScenarioStep): List<Problem> {
        val problems = mutableListOf<Problem>()
        val action = step.action
        if (action is StepAction.Do) problems += Problem.AiStep(step.id)
        if (action is StepAction.Run &&
            action.function !in READ_ONLY_FUNCTIONS
        ) {
            problems += Problem.WritingFunction(step.id, action.function)
        }
        step.assertions.forEach { assertion ->
            val writesOrAsksTheTestApi =
                when (assertion) {
                    is AssertionSpec.Oracle, is AssertionSpec.OnlyOneSucceeds -> true
                    is AssertionSpec.HttpStatus -> !assertion.method.equals("GET", ignoreCase = true)
                    is AssertionSpec.VisibleText, is AssertionSpec.NotVisible, is AssertionSpec.Count, is AssertionSpec.LatencyMax -> false
                }
            if (writesOrAsksTheTestApi) problems += Problem.WritingCheck(step.id, assertion.type)
        }
        if (step.emits?.idSource is IdSource.OracleField) problems += Problem.CreatedObject(step.id)
        return problems
    }
}
