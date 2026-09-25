/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.verification.domain

import az.petek.campaign.domain.TemplateContext
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TargetPathTest {
    @Test
    fun `ids and e-mails stay readable while structure characters are encoded`() {
        TargetPath.encode("42") shouldBe "42"
        TargetPath.encode("eli.k7x2.a07@test.kadrohr.com") shouldBe "eli.k7x2.a07@test.kadrohr.com"
        TargetPath.encode("a01+x@test.kadrohr.com") shouldBe "a01%2Bx@test.kadrohr.com"
        TargetPath.encode("42/../x?y=1#z") shouldBe "42%2F..%2Fx%3Fy%3D1%23z"
        TargetPath.encode("a&b c\\d%") shouldBe "a%26b%20c%5Cd%25"
    }

    @Test
    fun `non-ASCII values are encoded as UTF-8`() {
        TargetPath.encode("Aysel Məmmədova") shouldBe "Aysel%20M%C9%99mm%C9%99dova"
    }

    @Test
    fun `every template value is encoded`() {
        val encoded =
            TargetPath.encodeValues(
                TemplateContext(lastId = "a/b", self = mapOf("name" to "Ə b"), eventIds = mapOf("ticket_created" to "t 1")),
            )

        encoded shouldBe TemplateContext("a%2Fb", mapOf("name" to "%C6%8F%20b"), mapOf("ticket_created" to "t%201"))
        TargetPath.encodeValues(TemplateContext(null, emptyMap(), emptyMap())).lastId.shouldBeNull()
    }

    @Test
    fun `plain target paths are accepted`() {
        listOf(
            "/",
            "/api/tickets/42/approve",
            "/test/announcements/latest?by=eli.k7x2.a07@test.kadrohr.com",
            "/test/tickets/..%2Fx",
            "/files/report.v2/..x",
            "/test/x?next=/../y",
        ).forEach { TargetPath.problem(it).shouldBeNull() }
    }

    @Test
    fun `paths that could leave the target or climb out of their route are refused`() {
        listOf(
            "api/tickets/42",
            "https://kadrohr.com/api/tickets/42",
            "//kadrohr.com/api/tickets/42",
            "/api\\tickets",
            "/api/tickets/42 HTTP/1.1",
            "/api/tickets/42\r\nX-Injected: 1",
            "/api/tickets/../admin",
            "/api/tickets/./42",
            "/api/tickets/..",
            "/api/tickets/%2E%2e/admin",
            "/api/tickets/.%2e/admin",
            "",
        ).forEach { TargetPath.problem(it).shouldNotBeNull() }
    }
}
