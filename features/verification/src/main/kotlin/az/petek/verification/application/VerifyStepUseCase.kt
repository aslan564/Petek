/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.verification.application

import az.petek.campaign.domain.AssertionSpec
import az.petek.evidence.domain.AssertionRecord
import az.petek.verification.domain.ActorResult
import az.petek.verification.domain.AssertionInput

/**
 * Evaluates a step's assertions and records each result as evidence. Every browser-based verdict gets a
 * screenshot artifact and every oracle/HTTP verdict gets the response body artifact (CLAUDE.md rule 5).
 */
interface VerifyStepUseCase {
    suspend fun verifyActor(
        specs: List<AssertionSpec>,
        input: AssertionInput,
    ): List<AssertionRecord>

    suspend fun verifyGroup(
        specs: List<AssertionSpec>,
        input: AssertionInput,
        results: List<ActorResult>,
    ): List<AssertionRecord>
}
