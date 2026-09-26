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

package az.petek.app.cli

import az.petek.app.testing.CliHarness
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CliSessionTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a configuration is named by the env-file option or by a dot-env file, and nothing else`() {
        val cli = CliHarness(dir)

        CliSession(cli.runtime, envFile = null, verbose = false).hasConfigurationFile shouldBe false
        CliSession(cli.runtime, envFile = Path.of("staging.env"), verbose = false).hasConfigurationFile shouldBe true
        cli.write(".env", "PETEK_TARGET=https://from-dotenv.test\n")
        CliSession(cli.runtime, envFile = null, verbose = false).hasConfigurationFile shouldBe true
    }
}
