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

package az.petek.reporting.infrastructure

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LogFileTraceSourceTest {
    @TempDir
    lateinit var dir: Path

    private fun source(vararg lines: String): LogFileTraceSource {
        val file = dir.resolve("app.log").also { Files.writeString(it, lines.joinToString("\n")) }
        return LogFileTraceSource(file, secrets = listOf("dev-token-123"))
    }

    @Test
    fun `only lines carrying the id as a whole token are taken`() =
        runBlocking<Unit> {
            val lines =
                source(
                    "10:00 cor_ab POST /tickets 500",
                    "10:01 cor_abc GET /",
                    "10:02 id=cor_ab-2 GET /",
                    "10:03 [cor_ab] NullPointerException",
                ).lines("cor_ab")

            lines shouldContainExactly listOf("10:00 cor_ab POST /tickets 500", "10:03 [cor_ab] NullPointerException")
        }

    @Test
    fun `credentials in a log line never reach the report`() =
        runBlocking<Unit> {
            // A fake token assembled at run time, so secret scanners do not take the fixture for a real one.
            val jwt = listOf("eyJ" + "hbGciOiJub25lIn0", "eyJ" + "zdWIiOiJ0ZXN0In0", "ZmFrZS1zaWduYXR1cmU").joinToString(".")
            val line =
                source(
                    "cor_x Authorization: Bearer abc.def.ghi Cookie: sid=s3cr3t; theme=dark " +
                        "{\"password\":\"hunter22\",\"user\":\"eli\"} X-Test-Token: dev-token-123 " +
                        "jwt=$jwt",
                ).lines("cor_x").single()

            listOf("abc.def.ghi", "s3cr3t", "hunter22", "dev-token-123", jwt.substringBefore('.')).forEach { line shouldNotContain it }
            line.startsWith("cor_x") shouldBe true
        }
}
