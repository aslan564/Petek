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

package az.petek.verification.application

import az.petek.campaign.domain.AssertionSpec
import az.petek.evidence.domain.AssertionRecord
import az.petek.verification.domain.ActorResult
import az.petek.verification.domain.AssertionInput

/**
 * Evaluates a step's assertions and records each result as evidence. Every browser-based verdict gets a
 * screenshot artifact and every oracle/HTTP verdict gets the response body artifact (AGENTS.md rule 5).
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
