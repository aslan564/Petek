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

package az.petek.identity.application

import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityRegistryGenerator
import az.petek.identity.domain.IdentityRepository
import az.petek.identity.domain.IdentitySpec

/** Generates the identity registry for a run and stores it. Nothing is executed against the target. */
class PlanIdentitiesUseCase(
    private val generator: IdentityRegistryGenerator,
    private val repository: IdentityRepository,
) {
    /** The registry of the run [runId] that is starting, stored whole: its testers sign in with what is stored. */
    suspend fun execute(
        runId: RunId,
        runTag: RunTag,
        spec: IdentitySpec,
    ): IdentityPlan {
        val plan = generator.generate(spec, runTag)
        repository.replaceAll(runId, plan)
        return plan
    }

    /**
     * The registry of a run planned ahead (`petek plan`) under the plan id [planId]: generated as [execute] generates
     * it and returned whole, but stored [IdentityPlan.withoutOwnAccounts]. A plan never keeps the owner's passwords,
     * and never holds the e-mails of the owner's accounts against the runs that will sign in with them (an e-mail
     * belongs to one run only), so the campaign can be run, and planned again, afterwards. The whole registry is checked
     * as the run's is before a part of it is stored: one the run could not store (another tester with the name or
     * e-mail of an owner's account, [IdentityPlan.duplicates]) fails the plan too.
     */
    suspend fun planAhead(
        planId: RunId,
        runTag: RunTag,
        spec: IdentitySpec,
    ): IdentityPlan {
        val plan = generator.generate(spec, runTag)
        repository.replaceAll(planId, plan.withoutOwnAccounts())
        return plan
    }
}
