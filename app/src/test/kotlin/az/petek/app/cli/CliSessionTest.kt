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
import java.net.URI
import java.nio.file.Files
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

    @Test
    fun `without a configuration of its own a directory uses the owner's workspace, its paths inside the workspace`() {
        val cli = CliHarness(dir).apply { env.remove("PETEK_TARGET") }
        workspaceEnv(cli, "https://workspace-site.test")

        val session = CliSession(cli.runtime, envFile = null, verbose = false)

        session.hasConfigurationFile shouldBe true
        session.configurationDirectory shouldBe cli.workspace
        val config = session.loadConfig()
        config.target shouldBe URI("https://workspace-site.test")
        config.evidenceDir.startsWith(cli.workspace) shouldBe true
    }

    @Test
    fun `a directory's own dot-env and the env-file option still win over the workspace`() {
        val cli = CliHarness(dir).apply { env.remove("PETEK_TARGET") }
        workspaceEnv(cli, "https://workspace-site.test")
        cli.write(".env", "PETEK_TARGET=https://project-site.test\n")
        cli.write("staging.env", "PETEK_TARGET=https://staging-site.test\n")

        val local = CliSession(cli.runtime, envFile = null, verbose = false)
        local.configurationDirectory shouldBe dir
        local.loadConfig().target shouldBe URI("https://project-site.test")
        CliSession(cli.runtime, envFile = Path.of("staging.env"), verbose = false).loadConfig().target shouldBe
            URI("https://staging-site.test")
    }

    @Test
    fun `an environment that names the site is used as it always was, as CI does, and the workspace stays out`() {
        val cli = CliHarness(dir).apply { env["PETEK_TARGET"] = "https://ci-site.test" }
        workspaceEnv(cli, "https://workspace-site.test")

        val session = CliSession(cli.runtime, envFile = null, verbose = false)

        session.hasConfigurationFile shouldBe false
        session.namesSite shouldBe true
        session.configurationDirectory shouldBe dir
        session.loadConfig().target shouldBe URI("https://ci-site.test")
    }

    @Test
    fun `only with no file and no site in the environment is the owner asked for the site`() {
        val cli = CliHarness(dir).apply { env.remove("PETEK_TARGET") }

        CliSession(cli.runtime, envFile = null, verbose = false).namesSite shouldBe false
    }

    @Test
    fun `the setup page writes to the workspace, so the site is known from any directory`() {
        val cli = CliHarness(dir)

        CliSession(cli.runtime, envFile = null, verbose = false).setupEnvFile shouldBe cli.workspace.resolve(".env")
    }

    private fun workspaceEnv(
        cli: CliHarness,
        target: String,
    ) {
        Files.createDirectories(cli.workspace)
        Files.writeString(cli.workspace.resolve(".env"), "PETEK_TARGET=$target\nPETEK_EVIDENCE_DIR=evidence\n")
    }
}
