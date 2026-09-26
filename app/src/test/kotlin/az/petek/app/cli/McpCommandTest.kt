/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.PetekVersion
import az.petek.app.testing.CliHarness
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/** `petek mcp` end to end with production wiring: the owner's scenario file is imported and listed through MCP. */
class McpCommandTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `mcp speaks JSON-RPC over the runtime's streams and lists the imported scenario`() =
        runBlocking<Unit> {
            Files.createDirectories(dir.resolve("scenarios"))
            Files.writeString(dir.resolve("scenarios/tiny.yaml"), CliHarness.tinyCampaign())
            Files.writeString(dir.resolve(".env"), "PETEK_TARGET=${CliHarness.UNUSED_TARGET}\n")
            val requests =
                listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
                    """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_scenarios","arguments":{}}}""",
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_targets"}}""",
                )
            val output = ByteArrayOutputStream()
            val cli =
                CliHarness(dir).apply {
                    standardInput = ByteArrayInputStream((requests.joinToString("\n") + "\n").toByteArray())
                    standardOutput = output
                }

            val result = cli.run("mcp")

            result.statusCode shouldBe 0
            result.stdout shouldBe ""
            val answers =
                output
                    .toString(Charsets.UTF_8)
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { Json.parseToJsonElement(it).jsonObject }
            answers.map { it["id"]!!.jsonPrimitive.content } shouldContainAll listOf("1", "2", "3")
            val init = answers.single { it["id"]!!.jsonPrimitive.content == "1" }["result"]!!.jsonObject
            init["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content shouldBe PetekVersion.current
            val scenarios =
                answers
                    .single { it["id"]!!.jsonPrimitive.content == "2" }["result"]!!
                    .jsonObject["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            (Json.parseToJsonElement(scenarios) as JsonArray)
                .single()
                .jsonObject["name"]!!
                .jsonPrimitive.content shouldBe "tiny"
            val targets =
                answers
                    .single {
                        it["id"]!!.jsonPrimitive.content == "3"
                    }["result"]!!
                    .jsonObject["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            targets shouldContain "\"allowWrites\":false"
        }

    @Test
    fun `without a site to test the server answers the handshake and every tool asks the owner for one`() =
        runBlocking<Unit> {
            val requests =
                listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_targets"}}""",
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"explore_site","arguments":{"target":"https://site.example"}}}""",
                )
            val output = ByteArrayOutputStream()
            val cli =
                CliHarness(dir).apply {
                    standardInput = ByteArrayInputStream((requests.joinToString("\n") + "\n").toByteArray())
                    standardOutput = output
                }

            val result = cli.run("mcp")

            result.statusCode shouldBe 0
            val answers =
                output
                    .toString(Charsets.UTF_8)
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { Json.parseToJsonElement(it).jsonObject }
            answers.map { it["id"]!!.jsonPrimitive.content } shouldBe listOf("1", "2", "3")
            val targets =
                answers[1]["result"]!!
                    .jsonObject["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            targets shouldContain McpCommand.NO_TARGET
            val explore = answers[2]["result"]!!.jsonObject
            explore["isError"]!!.jsonPrimitive.content shouldBe "true"
            val text =
                explore["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            text shouldContain "Test olunacaq sayt verilməyib"
            text shouldContain "Sahibdən soruşun"
            Files.exists(dir.resolve("evidence")) shouldBe false
        }
}
