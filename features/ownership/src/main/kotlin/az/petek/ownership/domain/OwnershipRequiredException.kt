/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.domain

import az.petek.core.error.PetekException

/**
 * A full test was asked for on a site whose ownership is not proved, so nothing was written to it. The message tells
 * the owner exactly what to publish and where Pətək looked; the command line exits with 2.
 */
class OwnershipRequiredException(
    val status: OwnershipStatus.Unverified,
) : PetekException(describe(status)) {
    private companion object {
        fun describe(status: OwnershipStatus.Unverified): String {
            val challenge = status.challenge
            val dns = challenge.dnsName?.let { "\n  - or a DNS TXT record $it with the value: ${challenge.proofLine}" }.orEmpty()
            val looked =
                status.looked
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("; ", prefix = "\nLooked for it: ")
                    .orEmpty()
            return "Pətək writes to a site only after its owner proves ownership, so nothing was tested on ${challenge.host}. " +
                "Publish the proof once, then start again:" +
                "\n  - a file at ${challenge.fileUrl} containing the line: ${challenge.proofLine}" +
                dns + looked +
                "\n`petek verify` shows this again and checks it. Loopback and private-network addresses need no proof."
        }
    }
}
