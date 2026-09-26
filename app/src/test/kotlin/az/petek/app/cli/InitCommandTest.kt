/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.testing.CliHarness
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InitCommandTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `init prepares the working directory without loading a configuration`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, mapOf("PETEK_TARGET" to ""))

            val result = cli.run("init", "--target", "https://staging.example.com", "--ai", "claude")

            result.statusCode shouldBe 0
            result.stdout shouldContain "for claude (requested)"
            result.stdout shouldContain "created   .env — PETEK_TARGET=https://staging.example.com"
            result.stdout shouldContain "created   .claude/skills/petek/SKILL.md"
            result.stdout shouldContain "created   .mcp.json — server \"petek\""
            result.stdout shouldContain "petek doctor"
            Files.readString(dir.resolve(".env")) shouldContain "PETEK_TARGET=https://staging.example.com"
            Files.exists(dir.resolve("AGENTS.md")) shouldBe false
        }

    @Test
    fun `init can point at another directory and refuses an unknown agent`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            val other = dir.resolve("site")

            cli.run("init", "--dir", "site", "--ai", "codex").statusCode shouldBe 0
            Files.exists(other.resolve("AGENTS.md")) shouldBe true
            Files.exists(other.resolve(".petek/petek.yaml")) shouldBe true

            val refused = cli.run("init", "--ai", "chatgpt")
            refused.statusCode shouldNotBe 0
            refused.stderr shouldContain "unknown AI 'chatgpt'"
        }
}
