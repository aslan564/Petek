/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
