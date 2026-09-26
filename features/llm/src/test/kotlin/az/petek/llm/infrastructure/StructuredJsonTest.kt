/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
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
