/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.telemetry

import az.petek.core.telemetry.UsageSink
import az.petek.core.testing.FakeHarnessClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LocalFileUsageSinkTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `counters add up in memory and one JSON line is appended on close`() {
        val file = dir.resolve("telemetry/usage.jsonl")
        val sink = LocalFileUsageSink(file, FakeHarnessClock())

        sink.count("llm.calls", 1, UsageSink.Tags.NONE)
        sink.count("llm.calls", 2, UsageSink.Tags.NONE)
        sink.count("run.started", 1, UsageSink.Tags.of("testers" to "2-10", "provider" to "claude-cli"))
        sink.close()
        sink.close()

        val lines = Files.readAllLines(file)
        lines.size shouldBe 1
        lines.single() shouldContain "\"llm.calls\":3"
        lines.single() shouldContain "\"run.started{provider=claude-cli,testers=2-10}\":1"
    }

    @Test
    fun `nothing counted writes nothing`() {
        LocalFileUsageSink(dir.resolve("usage.jsonl"), FakeHarnessClock()).close()

        Files.exists(dir.resolve("usage.jsonl")) shouldBe false
    }

    @Test
    fun `tags carry machine words only, so no content can slip into telemetry`() {
        shouldThrow<IllegalArgumentException> { UsageSink.Tags.of("target" to "https://staging.example.com/page") }
        shouldThrow<IllegalArgumentException> { UsageSink.Tags.of("name" to "Aysel Məmmədova") }
        shouldThrow<IllegalArgumentException> {
            LocalFileUsageSink(
                dir.resolve("u"),
                FakeHarnessClock(),
            ).count("Bad Name", 1, UsageSink.Tags.NONE)
        }
    }

    @Test
    fun `tester counts are bucketed, never exact`() {
        listOf(1 to "1", 2 to "2-10", 10 to "2-10", 11 to "11-30", 30 to "11-30", 31 to "31-100", 101 to "101+").forEach { (n, bucket) ->
            CountingCampaignRunner.bucket(n) shouldBe bucket
        }
    }
}
