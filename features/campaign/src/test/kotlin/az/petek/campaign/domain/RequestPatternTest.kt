/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RequestPatternTest {
    @Test
    fun `the path regex must match the whole path`() {
        val approve = RequestPattern("POST", ".*/approve")

        approve.matches("POST", "/tickets/t2/approve") shouldBe true
        approve.matches("POST", "/api/tickets/t2/approve") shouldBe true
        approve.matches("POST", "/tickets/t2/approve-all") shouldBe false
        approve.matches("POST", "/approve/t2") shouldBe false
    }

    @Test
    fun `the method must be the named one, compared case-insensitively`() {
        val approve = RequestPattern("POST", ".*/approve")

        approve.matches("post", "/tickets/t2/approve") shouldBe true
        approve.matches("PUT", "/tickets/t2/approve") shouldBe false
    }

    @Test
    fun `no method means any mutating method but never a read`() {
        val any = RequestPattern(null, "/api/.*")

        listOf("POST", "PUT", "PATCH", "DELETE").map { any.matches(it, "/api/x") } shouldBe List(4) { true }
        any.matches("GET", "/api/x") shouldBe false
        any.matches("HEAD", "/api/x") shouldBe false
        RequestPattern.ANY_MUTATION.matches("DELETE", "/") shouldBe true
        RequestPattern.ANY_MUTATION.matches("GET", "/") shouldBe false
    }

    @Test
    fun `a pattern whose regex does not compile matches nothing instead of throwing`() {
        RequestPattern("POST", "/tickets/(").matches("POST", "/tickets/(") shouldBe false
    }

    @Test
    fun `the YAML form is a method and a path regex separated by whitespace`() {
        RequestPattern.parse("POST .*/approve") shouldBe RequestPattern("POST", ".*/approve")
        RequestPattern.parse("  patch\t /api/tickets/[^/]+ ") shouldBe RequestPattern("PATCH", "/api/tickets/[^/]+")
        RequestPattern.parse("* /api/.*") shouldBe RequestPattern(null, "/api/.*")
        RequestPattern.parse("POST /a b") shouldBe RequestPattern("POST", "/a b")
    }

    @Test
    fun `a text without both parts is not a pattern`() {
        RequestPattern.parse("/tickets/.*/approve").shouldBeNull()
        RequestPattern.parse("POST").shouldBeNull()
        RequestPattern.parse("   ").shouldBeNull()
    }

    @Test
    fun `describe gives the YAML form back`() {
        RequestPattern("POST", ".*/approve").describe() shouldBe "POST .*/approve"
        RequestPattern.ANY_MUTATION.describe() shouldBe "* .*"
    }

    @Test
    fun `only_one_succeeds without a request is decided by any mutating request`() {
        AssertionSpec.OnlyOneSucceeds().effectiveRequest shouldBe RequestPattern.ANY_MUTATION
        AssertionSpec.OnlyOneSucceeds(RequestPattern("POST", ".*")).effectiveRequest shouldBe RequestPattern("POST", ".*")
        AssertionSpec.OnlyOneSucceeds().type shouldBe "only_one_succeeds"
    }
}
