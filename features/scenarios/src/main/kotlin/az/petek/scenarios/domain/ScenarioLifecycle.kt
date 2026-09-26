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

import java.time.Instant

/**
 * The review state machine of scenario versions, as pure rules. It returns the updates to apply; the repository
 * applies them atomically (compare-and-set on the status), so two concurrent approvals cannot both win.
 *
 * ```
 * DRAFT --approve--> APPROVED --freeze--> FROZEN
 *                       |
 *                       +-- a newer version of the same name is approved --> SUPERSEDED
 * ```
 * - Approving a version supersedes the other APPROVED version of the same name, so each name has at most one.
 *   FROZEN versions are never superseded: they are baselines and stay exactly as they are.
 * - Approving an APPROVED or FROZEN version, or freezing a FROZEN one, changes nothing (idempotent).
 * - A SUPERSEDED version is not approved again (make a new draft from its text instead), and only an APPROVED
 *   version can be frozen.
 */
object ScenarioLifecycle {
    /** Updates that approve [target] at [at]; [sameName] are the stored versions of its name (it may include [target]). */
    fun approve(
        target: ScenarioVersion,
        sameName: List<ScenarioVersion>,
        at: Instant,
    ): List<ScenarioVersionUpdate> =
        when (target.status) {
            ScenarioStatus.APPROVED, ScenarioStatus.FROZEN -> {
                emptyList()
            }

            ScenarioStatus.SUPERSEDED -> {
                throw ScenarioTransitionException(
                    target,
                    "approve",
                    "it was superseded by ${target.supersededBy}; create a new draft from its text instead",
                )
            }

            ScenarioStatus.DRAFT -> {
                val superseded =
                    sameName
                        .filter { it.name == target.name && it.id != target.id && it.status == ScenarioStatus.APPROVED }
                        .map { ScenarioVersionUpdate(it, it.copy(status = ScenarioStatus.SUPERSEDED, supersededBy = target.id)) }
                // Superseding first keeps "one APPROVED version per name" true after every single statement.
                superseded + ScenarioVersionUpdate(target, target.copy(status = ScenarioStatus.APPROVED, approvedAt = at))
            }
        }

    /** Updates that freeze [target] at [at]. */
    fun freeze(
        target: ScenarioVersion,
        at: Instant,
    ): List<ScenarioVersionUpdate> =
        when (target.status) {
            ScenarioStatus.FROZEN -> {
                emptyList()
            }

            ScenarioStatus.APPROVED -> {
                listOf(ScenarioVersionUpdate(target, target.copy(status = ScenarioStatus.FROZEN, frozenAt = at)))
            }

            ScenarioStatus.DRAFT -> {
                throw ScenarioTransitionException(target, "freeze", "it is a draft; approve it first")
            }

            ScenarioStatus.SUPERSEDED -> {
                throw ScenarioTransitionException(target, "freeze", "only the approved version of a scenario can be frozen")
            }
        }

    /**
     * The version a campaign of [name] runs by default: the runnable version approved most recently (a newer
     * approval wins over an older frozen baseline). Null when no version of [name] was ever approved.
     */
    fun current(versions: List<ScenarioVersion>): ScenarioVersion? =
        versions
            .filter { it.runnable }
            .maxWithOrNull(compareBy<ScenarioVersion>({ it.approvedAt }, { it.version }))
}
