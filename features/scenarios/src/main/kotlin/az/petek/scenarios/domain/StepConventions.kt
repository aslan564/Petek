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

package az.petek.scenarios.domain

import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus

/**
 * How the harness writes step records, read back without depending on the agent or orchestration features:
 * - the orchestrator concludes each actor's action with one step whose action is `do: <task>`, `run <function>…` or
 *   `none` and whose detail is `<failure key>: <summary>`; `wait_for`, `emit`, assertion and group-check steps are
 *   conclusions of their own;
 * - inside a `do`, every LLM decision is a DO step (`click [12] "…"`, `report_problem <kind> "<note>"`), the last one
 *   with `| outcome: <STATUS> <failure key>: <summary>` in its detail;
 * - inside a `run`, sub-actions are `<function>: <what>` RUN steps and the function's own conclusion is `run <function>`;
 * - a publication is an EMIT step `emit <event>` (recorded even when the object id could not be read), a receiver's
 *   wait is a WAIT step `wait_for <event>` that fails with `not_received: …` when the event did not come in time.
 */
internal object StepConventions {
    const val PERMISSION_DENIED = "permission_denied"

    /** Keys of test-environment failures (inbox or model unreachable, one IP rate-limited), mirrored from the agent's failure reasons. */
    val ENVIRONMENT_KEYS: Set<String> = setOf("mail_unavailable", "llm_unavailable", "rate_limited")

    const val NOT_RECEIVED = "not_received"

    const val ONLY_ONE_SUCCEEDS = "only_one_succeeds"

    val FAILING: Set<StepStatus> = setOf(StepStatus.FAILED, StepStatus.ERROR, StepStatus.BLOCKED)

    private const val REPORT_PROBLEM = "report_problem "
    private const val DO_CONCLUSION = "do:"
    private const val RUN_CONCLUSION = "run "
    private const val EMIT = "emit "
    private const val WAIT_FOR = "wait_for "

    /** The event an EMIT step published, or null for any other step. */
    fun emittedEvent(step: StepRecord): String? = eventOf(step, StepKind.EMIT, EMIT)

    /** The event a WAIT step waited for, or null for any other step. */
    fun awaitedEvent(step: StepRecord): String? = eventOf(step, StepKind.WAIT, WAIT_FOR)

    private fun eventOf(
        step: StepRecord,
        kind: StepKind,
        prefix: String,
    ): String? =
        step.action
            .takeIf { step.kind == kind && it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private val leadingKey = Regex("^\\s*\\[?([a-z][a-z0-9]*(?:_[a-z0-9]+)+)]?\\s*:")
    private val outcomeKey = Regex("\\|\\s*outcome:\\s*[A-Z]+\\s+([a-z][a-z0-9]*(?:_[a-z0-9]+)*)\\s*:")

    enum class Role { PROBLEM_REPORT, CONCLUSION, INTERMEDIATE }

    fun roleOf(step: StepRecord): Role =
        when (step.kind) {
            StepKind.DO -> {
                when {
                    step.action.startsWith(REPORT_PROBLEM) -> Role.PROBLEM_REPORT
                    step.action.startsWith(DO_CONCLUSION) -> Role.CONCLUSION
                    else -> Role.INTERMEDIATE
                }
            }

            StepKind.RUN -> {
                if (step.action.startsWith(RUN_CONCLUSION)) Role.CONCLUSION else Role.INTERMEDIATE
            }

            StepKind.WAIT, StepKind.EMIT, StepKind.ASSERT, StepKind.SYSTEM -> {
                Role.CONCLUSION
            }
        }

    fun failing(step: StepRecord): Boolean = step.status in FAILING

    /** The machine-readable failure key of [step] (`permission_denied`, `not_received`, …), or null. */
    fun failureKey(step: StepRecord): String? {
        val detail = step.detail ?: return null
        return leadingKey.find(detail)?.groupValues?.get(1) ?: outcomeKey.find(detail)?.groupValues?.get(1)
    }
}
