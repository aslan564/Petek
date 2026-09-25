/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.testing.FakeBrowserSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ProgressReportingSessionTest {
    private val fake = FakeBrowserSession("a07")
    private var signals = 0
    private val session = ProgressReportingSession(fake) { signals++ }

    @Test
    fun `every browser call is delegated and reports progress when it starts and when it returns`() =
        runTest {
            session.navigate("/tickets")
            session.snapshot()
            session.click(1)
            session.fill(2, "Noutbuk", submit = true)
            session.select(3, "IT")
            session.clickSelector("#a")
            session.fillSelector("#b", "x")
            session.selectSelector("#c", "HR")
            session.readText("#d")
            session.readAttribute("#e", "data-id")
            session.waitForText("Elan", 1.seconds)
            session.waitForSelector("#f", 1.seconds)
            session.isTextVisible("Elan")
            session.isSelectorVisible("#f")
            session.count("#g")
            session.currentUrl() shouldBe "/tickets"
            session.screenshot()
            session.accessibilitySnapshot()
            session.domSnapshot()
            session.saveStorageState(Path.of("a07.json"))
            session.request("POST", "/api/tickets/1/approve")
            session.networkObservation()

            signals shouldBe 22 * 2
            session.label shouldBe "a07"
            fake.actions shouldContainExactly
                listOf(
                    "navigate /tickets",
                    "click 1",
                    "fill 2=Noutbuk +submit",
                    "select 3=IT",
                    "clickSelector #a",
                    "fillSelector #b=x",
                    "selectSelector #c=HR",
                    "saveStorageState a07.json",
                    "request POST /api/tickets/1/approve",
                )
        }

    @Test
    fun `a failing call still counts and its error reaches the agent unchanged`() =
        runTest {
            fake.failOn = { it.startsWith("click") }

            shouldThrow<BrowserActionException> { session.click(3) }.message shouldBe "scripted failure: click 3"
            signals shouldBe 2
        }

    @Test
    fun `closing the session is not progress`() =
        runTest {
            session.close()

            fake.closed shouldBe true
            signals shouldBe 0
        }
}
