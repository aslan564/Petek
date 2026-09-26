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

package az.petek.app.diagnostics

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** `<binary> --version`, for `petek doctor`: the first line it prints, or null when it cannot run or says nothing. */
internal object CliVersion {
    private const val TIMEOUT_SECONDS = 10L
    private const val MAX_CHARS = 80

    fun of(binary: String): String? {
        val output = File.createTempFile("petek-version-", ".txt")
        return try {
            val process =
                ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(output)
                    .redirectInput(ProcessBuilder.Redirect.from(File(if (File.separatorChar == '\\') "NUL" else "/dev/null")))
                    .start()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                return null
            }
            output
                .readLines()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(MAX_CHARS)
        } catch (_: IOException) {
            null
        } finally {
            output.delete()
        }
    }
}
