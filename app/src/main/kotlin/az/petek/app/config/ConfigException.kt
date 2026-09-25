/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

import az.petek.core.error.PetekException
import java.nio.file.Path

/**
 * The configuration cannot be used. [problems] lists every problem found (not just the first), so the user fixes
 * `.env` in one go. Messages name variables, never their values.
 */
open class ConfigException(
    val problems: List<String>,
    headline: String = "Invalid configuration",
) : PetekException(headline + ":\n" + problems.joinToString("\n") { "  - $it" })

/** A `.env` file with malformed lines; [problems] carry the line numbers. */
class EnvFileException(
    val file: Path?,
    problems: List<String>,
) : ConfigException(problems, headline = "Cannot read ${file ?: ".env"}")
