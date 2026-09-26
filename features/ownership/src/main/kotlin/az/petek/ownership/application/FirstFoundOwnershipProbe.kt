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

package az.petek.ownership.application

import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipProbe
import az.petek.ownership.domain.ProofLook
import java.net.URI

/** Asks [probes] in order and stops at the first that finds the proof; otherwise reports everything each one saw. */
class FirstFoundOwnershipProbe(
    private val probes: List<OwnershipProbe>,
) : OwnershipProbe {
    constructor(vararg probes: OwnershipProbe) : this(probes.toList())

    override suspend fun look(
        target: URI,
        challenge: OwnershipChallenge,
    ): ProofLook {
        val looked = mutableListOf<String>()
        for (probe in probes) {
            when (val result = probe.look(target, challenge)) {
                is ProofLook.Found -> return result
                is ProofLook.Missing -> looked += result.looked
            }
        }
        return ProofLook.Missing(looked)
    }
}
