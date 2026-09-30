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

package az.petek.app.runs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** One run at a time over one evidence store (the owner's decision of 2026-09-30). */
class RunLockTest {
    @TempDir
    lateinit var dir: Path

    private fun lock() = RunLock(dir.resolve("evidence").resolve(RunLock.FILE_NAME)) { Instant.parse("2026-09-30T10:00:00Z") }

    @Test
    fun `a second run is refused while the first holds the lock, naming who holds it, and may start once it is let go`() {
        val first = lock().acquire("petek run")

        val refused = shouldThrow<RunLockBusyException> { lock().acquire("the panel") }

        refused.holder shouldContain "petek run"
        refused.holder shouldContain "pid ${ProcessHandle.current().pid()}"
        refused.holder shouldContain "since 2026-09-30T10:00:00Z"
        first.close()
        lock().acquire("the panel").close()
        Files.readString(dir.resolve("evidence").resolve(RunLock.FILE_NAME)) shouldBe ""
    }
}
