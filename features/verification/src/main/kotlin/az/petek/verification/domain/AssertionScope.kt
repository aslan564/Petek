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

package az.petek.verification.domain

import az.petek.campaign.domain.AssertionSpec

/**
 * True for assertions judged once per step over all actors' results (`only_one_succeeds`) rather than per actor.
 * It decides which assertions `VerifyStepUseCase.verifyGroup` handles; `verifyActor` handles the rest.
 * The `when` is exhaustive on purpose: a new assertion type must choose its scope.
 */
val AssertionSpec.isGroupLevel: Boolean
    get() =
        when (this) {
            is AssertionSpec.OnlyOneSucceeds -> true

            is AssertionSpec.VisibleText,
            is AssertionSpec.NotVisible,
            is AssertionSpec.Oracle,
            is AssertionSpec.HttpStatus,
            is AssertionSpec.Count,
            is AssertionSpec.LatencyMax,
            -> false
        }
