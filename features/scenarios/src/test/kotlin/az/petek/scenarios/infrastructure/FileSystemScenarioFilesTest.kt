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
            val path = dir.resolve("nested/deeper/portal.yaml")
            val text = "\uFEFFcampaign:\r\n  departments: [Satış, Əməliyyat]\n"

            files.write(path, text, overwrite = false)

            Files.readAllBytes(path).toList() shouldBe text.toByteArray(Charsets.UTF_8).toList()
            files.read(path) shouldBe text
        }

    @Test
    fun `an existing file is never replaced without overwrite`() =
        runTest {
            val path = dir.resolve("portal.yaml")
            Files.writeString(path, "original\n")

            val error = shouldThrow<ScenarioFileException> { files.write(path, "new\n", overwrite = false) }

            error.message shouldContain "already exists"
            Files.readString(path) shouldBe "original\n"
        }

    @Test
    fun `with overwrite the file is replaced and no temporary file is left behind`() =
        runTest {
            val path = dir.resolve("portal.yaml")
            Files.writeString(path, "original\n")

            files.write(path, "new\n", overwrite = true)

            Files.readString(path) shouldBe "new\n"
            dir.listDirectoryEntries().map { it.fileName.toString() } shouldBe listOf("portal.yaml")
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
