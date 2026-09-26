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

package az.petek.llm.infrastructure.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OutputFilesTest {
    @TempDir
    lateinit var temp: Path

    private val bytes = ByteArray(100_000) { (it % 251).toByte() }

    private fun file(content: ByteArray): Path = Files.write(temp.resolve("out"), content)

    @Test
    fun `head keeps only the leading bytes`() {
        OutputFiles.head(file(bytes), limit = 10) shouldBe bytes.copyOfRange(0, 10)
    }

    @Test
    fun `head returns everything below the limit`() {
        OutputFiles.head(file(bytes), limit = 1_000_000) shouldBe bytes
    }

    @Test
    fun `tail keeps only the trailing bytes`() {
        OutputFiles.tail(file(bytes), limit = 300) shouldBe bytes.copyOfRange(bytes.size - 300, bytes.size)
    }

    @Test
    fun `tail returns everything below the limit`() {
        OutputFiles.tail(file(bytes.copyOf(50)), limit = 300) shouldBe bytes.copyOf(50)
    }

    @Test
    fun `a file the process never created reads as empty`() {
        val missing = temp.resolve("never-written")

        OutputFiles.head(missing, limit = 10).size shouldBe 0
        OutputFiles.tail(missing, limit = 10).size shouldBe 0
    }
}
