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

package az.petek.capacity.infrastructure

import az.petek.capacity.domain.Bytes.KIB
import az.petek.capacity.domain.CapacityMeasurementException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ProcProcessMemoryTest {
    @TempDir
    lateinit var proc: Path

    private fun process(
        pid: Long,
        pssKib: Long? = null,
        rssKib: Long? = null,
    ) {
        val dir = proc.resolve(pid.toString()).createDirectories()
        pssKib?.let { dir.resolve("smaps_rollup").writeText("55d0-7ffc ---p 00000000 00:00 0 [rollup]\nRss: 999999 kB\nPss:  $it kB\n") }
        rssKib?.let { dir.resolve("status").writeText("Name:\tchrome\nVmPeak:\t 999999 kB\nVmRSS:\t   $it kB\nThreads:\t12\n") }
    }

    @Test
    fun `the proportional set sizes of all descendants are added up`() {
        process(101, pssKib = 1_000, rssKib = 5_000)
        process(102, pssKib = 2_000, rssKib = 9_000)

        ProcProcessMemory(proc) { listOf(101, 102) }.descendantBytes() shouldBe 3_000 * KIB
    }

    @Test
    fun `without smaps_rollup the resident size is used`() {
        process(201, rssKib = 4_000)
        process(202, pssKib = 1_000)

        ProcProcessMemory(proc) { listOf(201, 202) }.descendantBytes() shouldBe 5_000 * KIB
    }

    @Test
    fun `a process that already ended counts as nothing`() {
        process(301, pssKib = 700)

        ProcProcessMemory(proc) { listOf(301, 302) }.descendantBytes() shouldBe 700 * KIB
    }

    @Test
    fun `no descendants use no memory`() {
        ProcProcessMemory(proc) { emptyList() }.descendantBytes() shouldBe 0
    }

    @Test
    fun `a system without proc cannot measure`() {
        shouldThrow<CapacityMeasurementException> {
            ProcProcessMemory(proc.resolve("missing")) { listOf(1) }.descendantBytes()
        }
    }

    @Test
    fun `a real child process is measured through proc`() {
        assumeTrue(Files.isDirectory(Path.of("/proc/self")), "needs Linux /proc")
        val child = ProcessBuilder("sleep", "30").start()
        try {
            ProcProcessMemory { listOf(child.pid()) }.descendantBytes() shouldBeGreaterThan 0
        } finally {
            child.destroyForcibly().waitFor()
        }
    }
}
