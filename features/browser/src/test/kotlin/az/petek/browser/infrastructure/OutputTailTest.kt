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

class OutputTailTest {
    @Test
    fun `only the last lines are kept`() {
        val tail = OutputTail(maxLines = 2)

        listOf("one", "two", "three").forEach(tail::add)

        tail.toString() shouldBe "two\nthree"
    }

    @Test
    fun `progress bar redraws keep only their final state and blank lines are skipped`() {
        val tail = OutputTail()

        tail.add("10%\r50%\r100%")
        tail.add("   ")

        tail.toString() shouldBe "100%"
    }

    @Test
    fun `an empty tail says so`() {
        OutputTail().toString() shouldBe "(no output)"
    }
}
