/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.application

import az.petek.core.time.HarnessClock
import az.petek.ownership.domain.HostLocality
import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipLedger
import az.petek.ownership.domain.OwnershipProbe
import az.petek.ownership.domain.OwnershipRecord
import az.petek.ownership.domain.OwnershipRequiredException
import az.petek.ownership.domain.OwnershipStatus
import az.petek.ownership.domain.OwnershipTokens
import az.petek.ownership.domain.ProofLook
import az.petek.ownership.domain.SiteHosts
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

/**
 * Decides whether Pətək may write to a site (ADR-0012, the owner's decision of 2026-09-26): a **full test** — a run,
 * sign-up, the explorer's logged-in and touching phases — needs the site's owner to have published the proof of
 * [OwnershipChallenge]; without it Pətək only reads. Nobody can then aim Pətək at somebody else's site and open accounts
 * there.
 *
 * - A host that is `localhost` or whose every address is loopback, private or link-local is [OwnershipStatus.Exempt].
 * - A proof found is remembered in the [ledger] and trusted for [maxAge]; after that the next [check] looks again, so a
 *   proof that was taken down stops counting within that period. Looking needs no command of its own: the owner
 *   publishes the proof and simply starts again.
 */
class SiteOwnership(
    private val tokens: OwnershipTokens,
    private val ledger: OwnershipLedger,
    private val probe: OwnershipProbe,
    private val locality: HostLocality,
    private val clock: HarnessClock,
    private val maxAge: Duration = DEFAULT_MAX_AGE,
) {
    /** What the owner of [target]'s host publishes. */
    fun challenge(target: URI): OwnershipChallenge = OwnershipChallenge.of(target, tokens.tokenFor(SiteHosts.of(target)))

    /** Exempt, a remembered proof younger than [maxAge], or else a fresh look for the proof. */
    suspend fun check(target: URI): OwnershipStatus {
        val host = SiteHosts.of(target)
        if (isExempt(host)) return OwnershipStatus.Exempt(host)
        ledger.find(host)?.takeIf(::isFresh)?.let { return OwnershipStatus.Verified(it) }
        return look(target)
    }

    /** Looks for the proof now, whatever is remembered (what `petek verify` does); an exempt host is not looked at. */
    suspend fun verify(target: URI): OwnershipStatus {
        val host = SiteHosts.of(target)
        return if (isExempt(host)) OwnershipStatus.Exempt(host) else look(target)
    }

    /**
     * Exempt, or the proof looked for now **without** reading or remembering anything: a diagnosis such as `petek doctor`
     * that must leave no trace (not even a database file).
     */
    suspend fun inspect(target: URI): OwnershipStatus {
        val host = SiteHosts.of(target)
        if (isExempt(host)) return OwnershipStatus.Exempt(host)
        val challenge = challenge(target)
        return when (val found = probe.look(target, challenge)) {
            is ProofLook.Found -> OwnershipStatus.Verified(OwnershipRecord(host, found.method, clock.now().wall))
            is ProofLook.Missing -> OwnershipStatus.Unverified(challenge, found.looked)
        }
    }

    /** [check], or [OwnershipRequiredException] when the site is not proved to be the caller's. */
    suspend fun requireFullTest(target: URI): OwnershipStatus {
        val status = check(target)
        if (status is OwnershipStatus.Unverified) throw OwnershipRequiredException(status)
        return status
    }

    private suspend fun isExempt(host: String): Boolean = SiteHosts.isLocalName(host) || locality.isLocal(host)

    private fun isFresh(record: OwnershipRecord): Boolean =
        java.time.Duration
            .between(record.verifiedAt, clock.now().wall)
            .toKotlinDuration() <= maxAge

    private suspend fun look(target: URI): OwnershipStatus {
        val challenge = challenge(target)
        return when (val found = probe.look(target, challenge)) {
            is ProofLook.Found -> {
                val record = OwnershipRecord(challenge.host, found.method, clock.now().wall)
                ledger.save(record)
                logger.info { "Ownership of ${challenge.host} verified by ${found.method.key}" }
                OwnershipStatus.Verified(record)
            }

            is ProofLook.Missing -> {
                OwnershipStatus.Unverified(challenge, found.looked)
            }
        }
    }

    companion object {
        /** How long a proof found is trusted before it is looked for again. */
        val DEFAULT_MAX_AGE: Duration = 30.days
    }
}
