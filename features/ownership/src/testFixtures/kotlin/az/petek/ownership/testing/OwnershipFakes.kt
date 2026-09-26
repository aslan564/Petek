/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.testing

import az.petek.core.time.HarnessClock
import az.petek.ownership.application.SiteOwnership
import az.petek.ownership.domain.HostLocality
import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipLedger
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.domain.OwnershipProbe
import az.petek.ownership.domain.OwnershipRecord
import az.petek.ownership.domain.OwnershipToken
import az.petek.ownership.domain.OwnershipTokens
import az.petek.ownership.domain.ProofLook
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** [OwnershipLedger] in memory. */
class InMemoryOwnershipLedger : OwnershipLedger {
    private val records = ConcurrentHashMap<String, OwnershipRecord>()

    override suspend fun find(host: String): OwnershipRecord? = records[host]

    override suspend fun save(record: OwnershipRecord) {
        records[record.host] = record
    }

    fun all(): List<OwnershipRecord> = records.values.sortedBy { it.host }
}

/** Finds the proof by [found] (null: never finds it) and records every target it was asked about. */
class ScriptedOwnershipProbe(
    @Volatile var found: OwnershipMethod? = OwnershipMethod.WELL_KNOWN_FILE,
) : OwnershipProbe {
    val looks: MutableList<URI> = CopyOnWriteArrayList()

    override suspend fun look(
        target: URI,
        challenge: OwnershipChallenge,
    ): ProofLook {
        looks += target
        return found?.let { ProofLook.Found(it) } ?: ProofLook.Missing(listOf("${challenge.fileUrl}: HTTP 404"))
    }
}

/** Local exactly for the hosts in [local]. */
class FixedHostLocality(
    private val local: Set<String> = emptySet(),
) : HostLocality {
    override suspend fun isLocal(host: String): Boolean = host in local
}

/** The same all-zero token for every host. */
object ZeroOwnershipTokens : OwnershipTokens {
    override fun tokenFor(host: String): OwnershipToken = OwnershipToken("0".repeat(32))
}

/** Builds a [SiteOwnership] from fakes for tests of the layers above it. */
object OwnershipTestKit {
    /** Every site is proved to be the caller's (the probe always finds the file), nothing is local. */
    fun owned(clock: HarnessClock): SiteOwnership = siteOwnership(clock, ScriptedOwnershipProbe())

    /** No site is proved (the probe never finds the proof); only [local] hosts are exempt. */
    fun unowned(
        clock: HarnessClock,
        local: Set<String> = emptySet(),
    ): SiteOwnership = siteOwnership(clock, ScriptedOwnershipProbe(found = null), local)

    fun siteOwnership(
        clock: HarnessClock,
        probe: OwnershipProbe,
        local: Set<String> = emptySet(),
        ledger: OwnershipLedger = InMemoryOwnershipLedger(),
    ): SiteOwnership = SiteOwnership(ZeroOwnershipTokens, ledger, probe, FixedHostLocality(local), clock)
}
