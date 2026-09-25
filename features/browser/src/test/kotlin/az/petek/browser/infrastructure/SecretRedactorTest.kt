/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.browser.infrastructure

import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SecretRedactorTest {
    @Test
    fun `textbox values that are secrets are masked however short they are`() {
        val yaml =
            """
            - text: Şifrə
            - textbox "Şifrə": ab
            - textbox "Ad": ab
            - paragraph: about
            """.trimIndent()

        SecretRedactor.redactAriaSnapshot(yaml, listOf("ab")) shouldBe
            """
            - text: Şifrə
            - textbox "Şifrə": ******
            - textbox "Ad": ******
            - paragraph: about
            """.trimIndent()
    }

    @Test
    fun `quoted YAML values and textbox attributes are understood`() {
        val yaml =
            """
            - textbox "Parol \"köhnə\"" [disabled]: "p: \"x\""
              - textbox "Yeni": keep
            """.trimIndent()

        SecretRedactor.redactAriaSnapshot(yaml, listOf("p: \"x\"")) shouldBe
            """
            - textbox "Parol \"köhnə\"" [disabled]: ******
              - textbox "Yeni": keep
            """.trimIndent()
    }

    @Test
    fun `longer secrets are masked anywhere in the text`() {
        SecretRedactor.redactText("<p>Your password is hunter22, hunter22!</p>", listOf("hunter22")) shouldBe
            "<p>Your password is ******, ******!</p>"
    }

    @Test
    fun `very short secrets do not shred free text`() {
        SecretRedactor.redactText("banana", listOf("an")) shouldBe "banana"
    }

    @Test
    fun `the longest secret wins when secrets overlap`() {
        SecretRedactor.redactText("x secret-long y", listOf("secret", "secret-long")) shouldBe "x ****** y"
    }

    @Test
    fun `a snapshot hides secrets in values names title url and text`() {
        val snapshot =
            PageSnapshot(
                url = "http://t/echo/hunter22",
                title = "hunter22",
                elements =
                    listOf(
                        PageElement(1, "textbox", "Şifrə", "input", null, "ab", enabled = true),
                        PageElement(2, "button", "Göndər hunter22", "button", null, null, enabled = true),
                        PageElement(3, "textbox", "Ad", "input", null, "x hunter22 y", enabled = true),
                        PageElement(4, "textbox", "Şəhər", "input", null, "Bakı", enabled = true),
                    ),
                visibleText = "Şifrəniz: hunter22",
            )

        val redacted = SecretRedactor.redactSnapshot(snapshot, listOf("hunter22", "ab"))

        redacted shouldBe
            PageSnapshot(
                url = "http://t/echo/******",
                title = "******",
                elements =
                    listOf(
                        PageElement(1, "textbox", "Şifrə", "input", null, "******", enabled = true),
                        PageElement(2, "button", "Göndər ******", "button", null, null, enabled = true),
                        PageElement(3, "textbox", "Ad", "input", null, "x ****** y", enabled = true),
                        PageElement(4, "textbox", "Şəhər", "input", null, "Bakı", enabled = true),
                    ),
                visibleText = "Şifrəniz: ******",
            )
    }

    @Test
    fun `a snapshot without secrets is returned as it is`() {
        val snapshot = PageSnapshot("http://t/", "Forma", listOf(PageElement(1, "textbox", "Ad", "input", null, "ab", true)), "ab")

        SecretRedactor.redactSnapshot(snapshot, emptyList()) shouldBe snapshot
        SecretRedactor.redactSnapshot(snapshot, listOf("")) shouldBe snapshot
    }

    @Test
    fun `no secrets leaves the text untouched`() {
        SecretRedactor.redactAriaSnapshot("- textbox \"Ad\": Aysel", emptyList()) shouldBe "- textbox \"Ad\": Aysel"
        SecretRedactor.redactAriaSnapshot("- textbox \"Ad\": Aysel", listOf("")) shouldBe "- textbox \"Ad\": Aysel"
    }
}
