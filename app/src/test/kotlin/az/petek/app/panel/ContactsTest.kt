/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContactsTest {
    @Test
    fun `e-mail addresses keep only their domain and everything else stays`() {
        Contacts.masked("a01: emit -> FAILED: oracle: GET /test/tickets/latest?by=tural.18mc.a01@test.kadrohr.com answered 404") shouldBe
            "a01: emit -> FAILED: oracle: GET /test/tickets/latest?by=***@test.kadrohr.com answered 404"
        Contacts.masked("eli+qa@kadro.test, vəli@x.az və mətn") shouldBe "***@kadro.test, ***@x.az və mətn"
        Contacts.masked("heç bir ünvan yoxdur @ burada") shouldBe "heç bir ünvan yoxdur @ burada"
    }
}
