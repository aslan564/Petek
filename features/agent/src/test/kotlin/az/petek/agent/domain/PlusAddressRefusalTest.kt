/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlusAddressRefusalTest {
    @Test
    fun `a plus address with an e-mail error on the page is recognised, in several languages`() {
        PlusAddressRefusal.detect("test+r1-a01@company.az", "Please enter a valid email address") shouldBe true
        PlusAddressRefusal.detect("test+r1-a01@company.az", "E-poçt ünvanı düzgün deyil") shouldBe true
        PlusAddressRefusal.detect("test+r1-a01@company.az", "Некорректный адрес почты") shouldBe true
    }

    @Test
    fun `no plus address, or no e-mail error, is not a plus refusal`() {
        PlusAddressRefusal.detect("anar.r1.a01@test.kadrohr.com", "Please enter a valid email address") shouldBe false
        PlusAddressRefusal.detect("test+r1-a01@company.az", "Password is too short") shouldBe false
    }
}
