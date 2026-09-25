/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.application

import az.petek.agent.domain.SharedRunState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * [SharedRunState] for one run inside one process. Writers replace the whole map atomically, and waiters suspend on
 * the state flow until their key appears (no polling), so any number of agents waiting for `company_code` cost nothing
 * until the admin publishes it. Keys are write-once: a later [put] of a different value is refused and the first value
 * stays (the others may already be acting on it).
 */
class InMemorySharedRunState : SharedRunState {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun get(key: String): String? = values.value[key]

    override fun put(
        key: String,
        value: String,
    ): Boolean = values.updateAndGet { if (key in it) it else it + (key to value) }[key] == value

    override suspend fun await(
        key: String,
        timeout: Duration,
    ): String? = get(key) ?: withTimeoutOrNull(timeout) { values.mapNotNull { it[key] }.first() }

    /** Everything published so far (for diagnostics and reports). */
    fun snapshot(): Map<String, String> = values.value
}
