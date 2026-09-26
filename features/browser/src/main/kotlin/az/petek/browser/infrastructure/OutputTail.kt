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

package az.petek.browser.infrastructure

/**
 * The last [maxLines] lines a child process printed, kept so that a startup failure can say why it failed
 * without buffering unbounded output. Thread-safe: reader threads append while the owner reads.
 */
internal class OutputTail(
    private val maxLines: Int = 40,
) {
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        val cleaned = line.substringAfterLast('\r').trimEnd()
        if (cleaned.isEmpty()) return
        synchronized(lines) {
            lines.addLast(cleaned)
            if (lines.size > maxLines) lines.removeFirst()
        }
    }

    override fun toString(): String =
        synchronized(lines) {
            if (lines.isEmpty()) "(no output)" else lines.joinToString(separator = "\n")
        }
}
