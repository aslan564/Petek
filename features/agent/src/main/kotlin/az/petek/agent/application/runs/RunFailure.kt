/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.application.runs

import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.core.error.PetekException

/** Ends a run function with a reportable reason. Thrown inside flows, turned into an outcome by [RunEngine]. */
internal class RunFailure(
    val reason: FailureReason,
    override val message: String,
    val status: ActionStatus = ActionStatus.FAILED,
) : PetekException(message)
