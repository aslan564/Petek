/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.application

/**
 * One earlier turn of a `do` execution as the model sees it again: what it did and what the harness observed.
 * [action] is the readable form recorded as evidence (placeholders, never secrets).
 */
data class ActionHistoryEntry(
    /** 1-based turn number within the execution. */
    val number: Int,
    val action: String,
    val observation: String,
)
