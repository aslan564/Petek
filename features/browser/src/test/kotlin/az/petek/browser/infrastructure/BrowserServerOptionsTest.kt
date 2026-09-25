/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.browser.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class BrowserServerOptionsTest {
    @Test
    fun `the server only ever listens on the loopback interface with a random port`() {
        BrowserServerOptions(headless = true).toJson() shouldBe
            """{"headless":true,"host":"127.0.0.1","port":0,"timeout":60000}"""
    }

    @Test
    fun `headed mode, launch timeout and executable path are passed on with JSON escaping`() {
        BrowserServerOptions(
            headless = false,
            launchTimeout = 5.seconds,
            executablePath = Path.of("/opt/my \"chrome\"\\bin\tx"),
        ).toJson() shouldBe
            """{"headless":false,"host":"127.0.0.1","port":0,"timeout":5000,"executablePath":"/opt/my \"chrome\"\\bin\u0009x"}"""
    }
}
