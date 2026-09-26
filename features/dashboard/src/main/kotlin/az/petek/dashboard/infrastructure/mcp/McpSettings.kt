/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
)
