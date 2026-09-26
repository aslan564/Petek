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

import kotlinx.coroutines.future.await

/**
 * [ProcessRunner] backed by [ProcessBuilder]: argument vector (no shell), explicit environment and directory, and
 * the standard streams redirected to the files named in the [ProcessSpec].
 */
class SystemProcessRunner : ProcessRunner {
    override fun start(spec: ProcessSpec): RunningProcess {
        val builder =
            ProcessBuilder(spec.command)
                .directory(spec.workingDirectory.toFile())
                .redirectInput(spec.stdinFile.toFile())
                .redirectOutput(spec.stdoutFile.toFile())
                .redirectError(spec.stderrFile.toFile())
        builder.environment().apply {
            clear()
            putAll(spec.environment)
        }
        return SystemRunningProcess(builder.start())
    }

    private class SystemRunningProcess(
        private val process: Process,
    ) : RunningProcess {
        override suspend fun awaitExit(): Int = process.onExit().await().exitValue()

        override fun destroyTree() {
            // Snapshot first: once the parent is gone its children are re-parented and no longer its descendants.
            val descendants = process.descendants().toList()
            process.destroyForcibly()
            descendants.forEach { it.destroyForcibly() }
        }
    }
}
