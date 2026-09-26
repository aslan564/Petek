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
