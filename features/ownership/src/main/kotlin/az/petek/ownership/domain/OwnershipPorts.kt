/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.domain

import java.net.URI

/** Remembers the hosts whose ownership was proved. */
interface OwnershipLedger {
    suspend fun find(host: String): OwnershipRecord?

    /** Stores [record], replacing an earlier one for the same host. */
    suspend fun save(record: OwnershipRecord)
}

/** Looks for the published proof of [OwnershipChallenge] for a target. Never throws for what it finds on the network. */
fun interface OwnershipProbe {
    suspend fun look(
        target: URI,
        challenge: OwnershipChallenge,
    ): ProofLook
}

/** Says whether every address of a host is local ([LocalAddresses]); a host that does not resolve is not local. */
fun interface HostLocality {
    suspend fun isLocal(host: String): Boolean
}

/** The ownership token of a host; the same for every machine that shares the key it is derived from. */
fun interface OwnershipTokens {
    fun tokenFor(host: String): OwnershipToken
}
