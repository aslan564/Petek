/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class StructuredJsonTest {
    private val expected = buildJsonObject { put("action", "click") }

    @Test
    fun `bare JSON is read as is`() {
        StructuredJson.parseObject("""{"action":"click"}""") shouldBe expected
    }

    @Test
    fun `a json code fence is removed`() {
        StructuredJson.parseObject("```json\n{\"action\": \"click\"}\n```") shouldBe expected
    }

    @Test
    fun `a plain code fence with surrounding whitespace is removed`() {
        StructuredJson.parseObject("  ```\n{\"action\": \"click\"}\n```  \n") shouldBe expected
    }

    @Test
    fun `a sentence around the object is ignored`() {
        StructuredJson.parseObject("Here is my decision: {\"action\": \"click\"} Done.") shouldBe expected
    }

    @Test
    fun `text that holds no JSON object is rejected`() {
        StructuredJson.parseObject("I cannot help with that.") shouldBe null
        StructuredJson.parseObject("") shouldBe null
        StructuredJson.parseObject("[1, 2]") shouldBe null
        StructuredJson.parseObject("{\"action\": ") shouldBe null
    }
}
