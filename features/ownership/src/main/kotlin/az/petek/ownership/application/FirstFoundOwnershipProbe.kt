/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
