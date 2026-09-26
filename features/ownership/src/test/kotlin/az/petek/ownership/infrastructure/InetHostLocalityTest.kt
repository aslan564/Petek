/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.infrastructure

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class InetHostLocalityTest {
    private val locality = InetHostLocality()

    @Test
    fun `loopback and private literals are local`() =
        runTest {
            locality.isLocal("127.0.0.1") shouldBe true
            locality.isLocal("::1") shouldBe true
            locality.isLocal("192.168.10.4") shouldBe true
        }

    @Test
    fun `a public literal is not local`() =
        runTest {
            locality.isLocal("8.8.8.8") shouldBe false
        }

    @Test
    fun `a name that does not resolve is not local`() =
        runTest {
            locality.isLocal("no-such-host.invalid") shouldBe false
        }
}
