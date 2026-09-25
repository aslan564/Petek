/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.domain

import az.petek.core.ids.RunId
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.StepRecord

/** The evidence of one run of a `--repeat` group that stability is computed from. */
data class RepeatRunEvidence(
    val runId: RunId,
    val assertions: List<AssertionRecord>,
    val steps: List<StepRecord>,
)
