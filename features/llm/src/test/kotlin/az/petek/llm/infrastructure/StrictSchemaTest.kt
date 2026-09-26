/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure

import az.petek.llm.LlmTestData
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

class StrictSchemaTest {
    @Test
    fun `optional properties become required and nullable, and objects forbid other properties`() {
        StrictSchema.of(LlmTestData.DECISION_SCHEMA).toString() shouldBe
            """{"type":"object","properties":{"action":{"type":"string","enum":["click"]},""" +
            """"ref":{"type":["integer","null"]}},"required":["action","ref"],"additionalProperties":false}"""
    }

    @Test
    fun `nested objects and array items are rewritten too, and a property without a type becomes anyOf null`() {
        val schema =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("steps") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("note") { put("type", "string") }
                                putJsonObject("value") { putJsonArray("enum") { add(JsonNull) } }
                            }
                        }
                    }
                }
                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("steps")) }
            }

        StrictSchema.of(schema).toString() shouldBe
            """{"type":"object","properties":{"steps":{"type":"array","items":{"type":"object","properties":""" +
            """{"note":{"type":["string","null"]},"value":{"anyOf":[{"enum":[null]},{"type":"null"}]}},""" +
            """"required":["note","value"],"additionalProperties":false}}},"required":["steps"],"additionalProperties":false}"""
    }

    @Test
    fun `nulls in the answer are removed so parsers read them as absent`() {
        val answer =
            buildJsonObject {
                put("action", "click")
                put("ref", JsonNull)
                putJsonArray("list") { add(buildJsonObject { put("a", JsonNull) }) }
            }

        StrictSchema.withoutNulls(answer) shouldBe
            buildJsonObject {
                put("action", "click")
                put("list", buildJsonArray { add(buildJsonObject { }) })
            }
    }
}
