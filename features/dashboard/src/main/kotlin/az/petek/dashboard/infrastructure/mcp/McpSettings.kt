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

package az.petek.dashboard.infrastructure.mcp

import java.nio.file.Path

/**
 * What the MCP server knows about the process it runs in.
 *
 * @property target the configured site under test (`PETEK_TARGET`), the default of every tool that takes a target.
 * @property evidenceDir where artifacts live; `get_evidence` returns absolute paths under it for the host AI to read.
 * @property allowWrites whether tools that change something (exploration with writes, runs, approvals, teardown)
 *   may be called; without it the server is read-only, whatever the caller asks.
 * @property version the server's version, reported in the handshake.
 */
data class McpSettings(
    val target: String,
    val evidenceDir: Path,
    val allowWrites: Boolean,
    val version: String,
    /** The other sites with a target profile (`targets/<name>.yaml`): name to URL. */
    val profiles: Map<String, String> = emptyMap(),
)
