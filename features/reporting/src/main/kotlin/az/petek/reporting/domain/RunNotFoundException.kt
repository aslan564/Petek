/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.domain

import az.petek.core.error.PetekException
import az.petek.core.ids.RunId

/** A report was requested for a run the evidence store does not know (e.g. a mistyped `petek report <run_id>`). */
class RunNotFoundException(
    val runId: RunId,
) : PetekException("Run '$runId' not found in the evidence store")
