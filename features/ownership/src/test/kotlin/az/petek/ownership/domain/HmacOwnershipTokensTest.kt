/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class HmacOwnershipTokensTest {
    private val tokens = HmacOwnershipTokens("team-secret".toByteArray())

    @Test
    fun `the same secret and host give the same token on every machine`() {
        tokens.tokenFor("stage.example.com") shouldBe HmacOwnershipTokens("team-secret".toByteArray()).tokenFor("stage.example.com")
    }

    @Test
    fun `another host or another secret gives another token`() {
        tokens.tokenFor("stage.example.com") shouldNotBe tokens.tokenFor("www.example.com")
        tokens.tokenFor("stage.example.com") shouldNotBe HmacOwnershipTokens("other".toByteArray()).tokenFor("stage.example.com")
    }

    @Test
    fun `the token is 128 bits as lowercase hex`() {
        tokens.tokenFor("stage.example.com").value.length shouldBe 32
    }
}
