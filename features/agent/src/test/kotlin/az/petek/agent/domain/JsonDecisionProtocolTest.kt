/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.seconds

class JsonDecisionProtocolTest {
    private val protocol = JsonDecisionProtocol()

    private fun parse(json: String): DecisionParse = protocol.parse(Json.parseToJsonElement(json).jsonObject)

    private fun action(json: String): AgentAction = parse(json).shouldBeInstanceOf<DecisionParse.Valid>().decision.action

    private fun error(json: String): String = parse(json).shouldBeInstanceOf<DecisionParse.Invalid>().error

    @ParameterizedTest(name = "{0}")
    @MethodSource("validDecisions")
    fun `every tool parses into its whitelisted action`(
        json: String,
        expected: AgentAction,
    ) {
        val decision = parse(json).shouldBeInstanceOf<DecisionParse.Valid>().decision
        decision.action shouldBe expected
        decision.reason shouldBe "because"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDecisions")
    fun `missing or wrong arguments are rejected with a precise message`(
        json: String,
        expectedMessagePart: String,
    ) {
        error(json) shouldContain expectedMessagePart
    }

    @Test
    fun `tool names in the schema, the parser and the actions agree`() {
        protocol.toolNames shouldContainExactly
            listOf(
                "navigate",
                "click",
                "type",
                "select",
                "read_text",
                "wait_text",
                "get_email_code",
                "get_phone_code",
                "done",
                "report_problem",
            )
        validDecisions().map { it.get()[1] as AgentAction }.map { it.toolName }.toSet() shouldBe protocol.toolNames.toSet()
    }

    @Test
    fun `an unknown tool lists the allowed tools`() {
        val message = error("""{"reason": "r", "tool": "execute_js", "text": "alert(1)"}""")
        message shouldContain "Unknown tool 'execute_js'"
        protocol.toolNames.forEach { message shouldContain it }
    }

    @Test
    fun `a missing tool or reason is invalid`() {
        error("""{"reason": "r"}""") shouldContain "Missing required field 'tool'"
        error("""{"tool": "click", "ref": 1}""") shouldContain "Missing required field 'reason'"
        error("""{"reason": "   ", "tool": "click", "ref": 1}""") shouldContain "Missing required field 'reason'"
    }

    @Test
    fun `tool names are matched case-insensitively and trimmed`() {
        action("""{"reason": "because", "tool": " CLICK ", "ref": 3}""") shouldBe AgentAction.Click(3)
    }

    @Test
    fun `wait timeout defaults to 10 seconds and is clamped to 1-60`() {
        (action("""{"reason": "r", "tool": "wait_text", "text": "Hazır"}""") as AgentAction.WaitText).timeout shouldBe 10.seconds
        (action("""{"reason": "r", "tool": "wait_text", "text": "Hazır", "timeout_s": 0}""") as AgentAction.WaitText).timeout shouldBe
            1.seconds
        (action("""{"reason": "r", "tool": "wait_text", "text": "Hazır", "timeout_s": -5}""") as AgentAction.WaitText).timeout shouldBe
            1.seconds
        (action("""{"reason": "r", "tool": "wait_text", "text": "Hazır", "timeout_s": 600}""") as AgentAction.WaitText).timeout shouldBe
            60.seconds
        (action("""{"reason": "r", "tool": "wait_text", "text": "Hazır", "timeout_s": 25}""") as AgentAction.WaitText).timeout shouldBe
            25.seconds
    }

    @Test
    fun `JSON null means absent and numeric strings are accepted`() {
        action("""{"reason": "r", "tool": "click", "ref": "7", "text": null, "url": null}""") shouldBe AgentAction.Click(7)
        action("""{"reason": "r", "tool": "click", "ref": 7.0}""") shouldBe AgentAction.Click(7)
        action("""{"reason": "r", "tool": "type", "ref": 2, "text": "x", "submit": "true"}""") shouldBe AgentAction.Type(2, "x", true)
        action("""{"reason": "r", "tool": "done", "summary": "ok", "success": null}""") shouldBe AgentAction.Done("ok", true, null)
    }

    @Test
    fun `typed text is kept verbatim, including spaces and empty text that clears a field`() {
        action("""{"reason": "r", "tool": "type", "ref": 2, "text": "  {self.password} "}""") shouldBe
            AgentAction.Type(2, "  {self.password} ", false)
        action("""{"reason": "r", "tool": "type", "ref": 2, "text": ""}""") shouldBe AgentAction.Type(2, "", false)
    }

    @Test
    fun `done keeps a trimmed object id and drops a blank one`() {
        action("""{"reason": "r", "tool": "done", "summary": "Elan yaradıldı", "object_id": " a42 "}""") shouldBe
            AgentAction.Done("Elan yaradıldı", true, "a42")
        action("""{"reason": "r", "tool": "done", "summary": "x", "object_id": "  ", "success": false}""") shouldBe
            AgentAction.Done("x", false, null)
    }

    @Test
    fun `problem kinds are normalised and unknown kinds become other`() {
        (action("""{"reason": "r", "tool": "report_problem", "kind": "PERMISSION-DENIED", "note": "403"}""") as AgentAction.ReportProblem)
            .kind shouldBe ProblemKind.PERMISSION_DENIED
        (action("""{"reason": "r", "tool": "report_problem", "kind": "unexpected ui", "note": "n"}""") as AgentAction.ReportProblem)
            .kind shouldBe ProblemKind.UNEXPECTED_UI
        (action("""{"reason": "r", "tool": "report_problem", "kind": "weird", "note": "n"}""") as AgentAction.ReportProblem)
            .kind shouldBe ProblemKind.OTHER
        (action("""{"reason": "r", "tool": "report_problem", "note": "n"}""") as AgentAction.ReportProblem).kind shouldBe ProblemKind.OTHER
    }

    @Test
    fun `navigation accepts site paths and http(s) URLs only`() {
        action("""{"reason": "r", "tool": "navigate", "url": "/tickets?status=open"}""") shouldBe
            AgentAction.Navigate("/tickets?status=open")
        action("""{"reason": "r", "tool": "navigate", "url": "https://staging.kadrohr.com/x"}""") shouldBe
            AgentAction.Navigate("https://staging.kadrohr.com/x")
        action("""{"reason": "r", "tool": "navigate", "url": "HTTP://host/"}""") shouldBe AgentAction.Navigate("HTTP://host/")
        listOf("//evil.example/x", "tickets", "javascript:alert(1)", "file:///etc/passwd", "ftp://host/x", "https:///nohost", "/a b")
            .forEach { url -> error("""{"reason": "r", "tool": "navigate", "url": "$url"}""") shouldContain "'url' must be a path" }
    }

    @Test
    fun `the schema is strict-compatible and lists every tool and problem kind`() {
        val schema = protocol.responseSchema()
        schema["type"]?.jsonPrimitive?.content shouldBe "object"
        schema["additionalProperties"] shouldBe JsonPrimitive(false)
        schema["required"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("reason", "tool")
        val properties = schema["properties"]!!.jsonObject
        properties.keys shouldContainExactlyInAnyOrder
            listOf(
                "reason",
                "tool",
                "ref",
                "text",
                "url",
                "selector",
                "option",
                "submit",
                "timeout_s",
                "summary",
                "success",
                "object_id",
                "kind",
                "note",
            )
        properties.enumOf("tool") shouldContainExactly protocol.toolNames
        properties.enumOf("kind") shouldContainExactly ProblemKind.entries.map { it.key }
        properties.typeOf("ref") shouldBe "integer"
        properties.typeOf("timeout_s") shouldBe "integer"
        properties.typeOf("submit") shouldBe "boolean"
        properties.typeOf("success") shouldBe "boolean"
        properties.typeOf("summary") shouldBe "string"
        schema.toString().let { text ->
            listOf("minimum", "maximum", "minLength", "maxLength", "oneOf", "pattern").forEach { keyword ->
                text.contains("\"$keyword\"") shouldBe false
            }
        }
    }

    @Test
    fun `the tool reference describes every tool and the placeholders`() {
        val reference = protocol.describeTools()
        protocol.toolNames.forEach { reference shouldContain "- $it(" }
        reference shouldContain "{vars.email_code}"
        reference shouldContain "{vars.phone_code}"
        reference shouldContain "permission_denied"
    }

    private fun JsonObject.enumOf(name: String) = this[name]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.typeOf(name: String) = this[name]!!.jsonObject["type"]!!.jsonPrimitive.content

    companion object {
        @JvmStatic
        fun validDecisions(): List<Arguments> =
            listOf(
                Arguments.of(
                    """{"reason": "because", "tool": "navigate", "url": "/announcements"}""",
                    AgentAction.Navigate("/announcements"),
                ),
                Arguments.of("""{"reason": "because", "tool": "click", "ref": 12}""", AgentAction.Click(12)),
                Arguments.of(
                    """{"reason": "because", "tool": "type", "ref": 5, "text": "{self.password}", "submit": true}""",
                    AgentAction.Type(5, "{self.password}", true),
                ),
                Arguments.of("""{"reason": "because", "tool": "type", "ref": 5, "text": "Salam"}""", AgentAction.Type(5, "Salam", false)),
                Arguments.of("""{"reason": "because", "tool": "select", "ref": 4, "option": "IT"}""", AgentAction.Select(4, "IT")),
                Arguments.of(
                    """{"reason": "because", "tool": "read_text", "selector": "[data-testid=\"ticket-status\"]"}""",
                    AgentAction.ReadText("[data-testid=\"ticket-status\"]"),
                ),
                Arguments.of(
                    """{"reason": "because", "tool": "wait_text", "text": "Sabah 10:00", "timeout_s": 20}""",
                    AgentAction.WaitText("Sabah 10:00", 20.seconds),
                ),
                Arguments.of("""{"reason": "because", "tool": "get_email_code"}""", AgentAction.GetEmailCode),
                Arguments.of("""{"reason": "because", "tool": "get_phone_code"}""", AgentAction.GetPhoneCode),
                Arguments.of("""{"reason": "because", "tool": " GET_PHONE_CODE ", "ref": null}""", AgentAction.GetPhoneCode),
                Arguments.of(
                    """{"reason": "because", "tool": "done", "summary": "Elan yaradıldı", "success": true, "object_id": "a1"}""",
                    AgentAction.Done("Elan yaradıldı", true, "a1"),
                ),
                Arguments.of(
                    """{"reason": "because", "tool": "report_problem", "kind": "permission_denied", "note": "403"}""",
                    AgentAction.ReportProblem(ProblemKind.PERMISSION_DENIED, "403"),
                ),
            )

        @JvmStatic
        fun invalidDecisions(): List<Arguments> =
            listOf(
                Arguments.of("""{"reason": "r", "tool": "navigate"}""", "Tool 'navigate' needs 'url'"),
                Arguments.of("""{"reason": "r", "tool": "navigate", "url": "  "}""", "Tool 'navigate' needs 'url'"),
                Arguments.of("""{"reason": "r", "tool": "click"}""", "Tool 'click' needs 'ref'"),
                Arguments.of("""{"reason": "r", "tool": "click", "ref": 0}""", "'ref' must be an element number ≥ 1"),
                Arguments.of("""{"reason": "r", "tool": "click", "ref": -3}""", "'ref' must be an element number ≥ 1"),
                Arguments.of("""{"reason": "r", "tool": "click", "ref": "abc"}""", "'ref' must be an integer, was \"abc\""),
                Arguments.of("""{"reason": "r", "tool": "click", "ref": 2.5}""", "'ref' must be an integer, was 2.5"),
                Arguments.of("""{"reason": "r", "tool": "click", "ref": [1]}""", "'ref' must be a single value"),
                Arguments.of("""{"reason": "r", "tool": "type", "text": "x"}""", "Tool 'type' needs 'ref'"),
                Arguments.of("""{"reason": "r", "tool": "type", "ref": 1}""", "Tool 'type' needs 'text'"),
                Arguments.of(
                    """{"reason": "r", "tool": "type", "ref": 1, "text": "x", "submit": "yes"}""",
                    "'submit' must be true or false",
                ),
                Arguments.of("""{"reason": "r", "tool": "select", "ref": 1}""", "Tool 'select' needs 'option'"),
                Arguments.of("""{"reason": "r", "tool": "select", "option": "IT"}""", "Tool 'select' needs 'ref'"),
                Arguments.of("""{"reason": "r", "tool": "read_text"}""", "Tool 'read_text' needs 'selector'"),
                Arguments.of("""{"reason": "r", "tool": "wait_text"}""", "Tool 'wait_text' needs 'text'"),
                Arguments.of("""{"reason": "r", "tool": "wait_text", "text": " "}""", "Tool 'wait_text' needs 'text'"),
                Arguments.of(
                    """{"reason": "r", "tool": "wait_text", "text": "x", "timeout_s": "soon"}""",
                    "'timeout_s' must be an integer",
                ),
                Arguments.of("""{"reason": "r", "tool": "done"}""", "Tool 'done' needs 'summary'"),
                Arguments.of("""{"reason": "r", "tool": "done", "summary": "ok", "success": 1}""", "'success' must be true or false"),
                Arguments.of("""{"reason": "r", "tool": "report_problem", "kind": "bug"}""", "Tool 'report_problem' needs 'note'"),
                Arguments.of(
                    """{"reason": "r", "tool": "report_problem", "kind": {"x": 1}, "note": "n"}""",
                    "'kind' must be a single value",
                ),
            )
    }
}
