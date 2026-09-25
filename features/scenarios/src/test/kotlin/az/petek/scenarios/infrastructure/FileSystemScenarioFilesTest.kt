/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.scenarios.infrastructure

import az.petek.scenarios.domain.ScenarioFileException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

class FileSystemScenarioFilesTest {
    @TempDir
    lateinit var dir: Path

    private val files = FileSystemScenarioFiles()

    @Test
    fun `text is written and read back byte exact`() =
        runTest {
            val path = dir.resolve("nested/deeper/kadrohr.yaml")
            val text = "\uFEFFcampaign:\r\n  departments: [Satış, Əməliyyat]\n"

            files.write(path, text, overwrite = false)

            Files.readAllBytes(path).toList() shouldBe text.toByteArray(Charsets.UTF_8).toList()
            files.read(path) shouldBe text
        }

    @Test
    fun `an existing file is never replaced without overwrite`() =
        runTest {
            val path = dir.resolve("kadrohr.yaml")
            Files.writeString(path, "original\n")

            val error = shouldThrow<ScenarioFileException> { files.write(path, "new\n", overwrite = false) }

            error.message shouldContain "already exists"
            Files.readString(path) shouldBe "original\n"
        }

    @Test
    fun `with overwrite the file is replaced and no temporary file is left behind`() =
        runTest {
            val path = dir.resolve("kadrohr.yaml")
            Files.writeString(path, "original\n")

            files.write(path, "new\n", overwrite = true)

            Files.readString(path) shouldBe "new\n"
            dir.listDirectoryEntries().map { it.fileName.toString() } shouldBe listOf("kadrohr.yaml")
        }

    @Test
    fun `a missing file, a directory and invalid UTF-8 are clear errors`() =
        runTest {
            shouldThrow<ScenarioFileException> { files.read(dir.resolve("missing.yaml")) }.message shouldContain "does not exist"
            shouldThrow<ScenarioFileException> { files.read(dir) }.message shouldContain "cannot be read"
            val latin1 = dir.resolve("latin1.yaml")
            Files.write(latin1, byteArrayOf(0x61, 0xE7.toByte(), 0x0A))
            shouldThrow<ScenarioFileException> { files.read(latin1) }.message shouldContain "not valid UTF-8"
        }

    @Test
    fun `a file cannot be written where a directory stands`() =
        runTest {
            val occupied = dir.resolve("occupied")
            Files.createDirectories(occupied.resolve("child"))

            shouldThrow<ScenarioFileException> { files.write(occupied, "x", overwrite = true) }

            occupied.resolve("child").listDirectoryEntries().shouldBeEmpty()
        }
}
