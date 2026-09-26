/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.application

import az.petek.core.testing.FakeHarnessClock
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.domain.OwnershipRequiredException
import az.petek.ownership.domain.OwnershipStatus
import az.petek.ownership.testing.InMemoryOwnershipLedger
import az.petek.ownership.testing.OwnershipTestKit
import az.petek.ownership.testing.ScriptedOwnershipProbe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class SiteOwnershipTest {
    private val clock = FakeHarnessClock()
    private val probe = ScriptedOwnershipProbe()
    private val ledger = InMemoryOwnershipLedger()
    private val ownership = OwnershipTestKit.siteOwnership(clock, probe, local = setOf("kadro.test"), ledger = ledger)
    private val stage = URI("https://stage.example.com/login")

    @Test
    fun `localhost, names under it and hosts that resolve locally need no proof and are not looked at`() =
        runTest {
            ownership.check(URI("http://localhost:18080/")) shouldBe OwnershipStatus.Exempt("localhost")
            ownership.check(URI("http://shop.localhost/")) shouldBe OwnershipStatus.Exempt("shop.localhost")
            ownership.check(URI("http://kadro.test/")) shouldBe OwnershipStatus.Exempt("kadro.test")
            probe.looks.shouldBeEmpty()
        }

    @Test
    fun `a proof found is remembered with the harness time`() =
        runTest {
            val status = ownership.check(stage).shouldBeInstanceOf<OwnershipStatus.Verified>()

            status.record.method shouldBe OwnershipMethod.WELL_KNOWN_FILE
            status.record.verifiedAt shouldBe clock.now().wall
            ledger.all().single().host shouldBe "stage.example.com"
        }

    @Test
    fun `a remembered proof is trusted for 30 days without looking again`() =
        runTest {
            ownership.check(stage)
            clock.advance(29.days)

            ownership.check(stage).shouldBeInstanceOf<OwnershipStatus.Verified>()
            probe.looks shouldHaveSize 1
        }

    @Test
    fun `after 30 days the proof is looked for again and stops counting once it is gone`() =
        runTest {
            ownership.check(stage)
            clock.advance(30.days + 1.hours)
            probe.found = null

            ownership.check(stage).shouldBeInstanceOf<OwnershipStatus.Unverified>()
            probe.looks shouldHaveSize 2
        }

    @Test
    fun `verify always looks, whatever is remembered`() =
        runTest {
            ownership.check(stage)
            probe.found = OwnershipMethod.DNS_TXT

            ownership
                .verify(stage)
                .shouldBeInstanceOf<OwnershipStatus.Verified>()
                .record.method shouldBe OwnershipMethod.DNS_TXT
            probe.looks shouldHaveSize 2
        }

    @Test
    fun `a full test on a site without the proof is refused with what to publish and where Pətək looked`() =
        runTest {
            probe.found = null

            val refusal = shouldThrow<OwnershipRequiredException> { ownership.requireFullTest(stage) }

            refusal.status.challenge.host shouldBe "stage.example.com"
            refusal.status.looked shouldBe listOf("https://stage.example.com/.well-known/petek-verification.txt: HTTP 404")
            ledger.all().shouldBeEmpty()
        }

    @Test
    fun `inspect looks without remembering anything`() =
        runTest {
            ownership.inspect(stage).shouldBeInstanceOf<OwnershipStatus.Verified>()
            ownership.inspect(stage).shouldBeInstanceOf<OwnershipStatus.Verified>()

            probe.looks shouldHaveSize 2
            ledger.all().shouldBeEmpty()
        }

    @Test
    fun `an unverified site does not allow writes, an exempt or verified one does`() =
        runTest {
            ownership.check(URI("http://localhost/")).allowsWrites shouldBe true
            ownership.check(stage).allowsWrites shouldBe true
            probe.found = null
            ownership.check(URI("https://other.example.com/")).allowsWrites shouldBe false
        }
}
