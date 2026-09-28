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

package az.petek.orchestration.domain

import az.petek.identity.domain.Identity

/**
 * How a run cuts its testers into waves (`campaign.wave_size`, Faza 21): in agent order, `size` testers each. Empty
 * when there is no wave size or everyone fits into one wave; then everyone is live at once. The runner and the checks
 * that warn before a run both use it, so they always agree on who is in which wave.
 */
object Waves {
    fun of(
        identities: List<Identity>,
        size: Int?,
    ): List<List<Identity>> {
        if (size == null) return emptyList()
        val waves = identities.sortedBy { it.agentId }.chunked(size)
        return if (waves.size > 1) waves else emptyList()
    }
}
