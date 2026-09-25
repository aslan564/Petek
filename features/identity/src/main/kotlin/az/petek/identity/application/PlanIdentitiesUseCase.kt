/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
    suspend fun execute(
        runId: RunId,
        runTag: RunTag,
        spec: IdentitySpec,
    ): IdentityPlan {
        val plan = generator.generate(spec, runTag)
        repository.replaceAll(runId, plan)
        return plan
    }
}
