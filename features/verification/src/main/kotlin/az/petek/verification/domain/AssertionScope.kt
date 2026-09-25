/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
