/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class WorkingLanguageTest {
    @Test
    fun `auto follows the owner's own text and never forces a language`() {
        WorkingLanguage.of(null) shouldBe WorkingLanguage.AUTO
        WorkingLanguage.of(" Auto ") shouldBe WorkingLanguage.AUTO
        WorkingLanguage.AUTO.isAuto shouldBe true
        val rule = WorkingLanguage.AUTO.rule("purpose and unknowns")
        rule shouldContain "Write purpose and unknowns in the language the owner's own text"
        rule shouldContain "language of the page"
        rule shouldNotContain "in Azerbaijani"
    }

    @Test
    fun `a named language is used whatever the page or the owner wrote`() {
        val english = WorkingLanguage.of("English")
        english.isAuto shouldBe false
        english.toString() shouldBe "English"
        english.rule("rationale and summary") shouldBe
            "Write rationale and summary in English, whatever language the page or the owner's text uses."
        shouldThrow<IllegalArgumentException> { WorkingLanguage("  ") }
    }
}
