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

package az.petek.llm.application

import az.petek.llm.domain.TokenUsage

/**
 * Accumulated LLM usage of one agent (or of the whole run).
 * [costUsd] stays `null` while no call reported a cost (the Anthropic API does not), so a report can say "unknown"
 * instead of a misleading `$0.00`.
 */
data class UsageTotals(
    val calls: Long = 0,
    val failedCalls: Long = 0,
    val tokens: TokenUsage = TokenUsage(),
    val costUsd: Double? = null,
) {
    operator fun plus(other: UsageTotals): UsageTotals =
        UsageTotals(
            calls = calls + other.calls,
            failedCalls = failedCalls + other.failedCalls,
            tokens = tokens + other.tokens,
            costUsd = sumOrNull(costUsd, other.costUsd),
        )

    companion object {
        val EMPTY = UsageTotals()

        internal fun sumOrNull(
            a: Double?,
            b: Double?,
        ): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
    }
}
