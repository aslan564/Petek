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
import az.petek.app.testing.FakeBrowserEngine
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CapacityCommandTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `capacity recommends from the estimates without opening a browser`() =
        runBlocking<Unit> {
            val browser = FakeBrowserEngine()
            val cli = CliHarness(dir, browser = browser)

            val result = cli.run("capacity")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Recommended maximum: "
            result.stdout shouldContain " testers at once (limited by "
            result.stdout shouldContain "Per session"
            result.stdout shouldContain "(estimate)"
            result.stdout shouldContain "This is a recommendation, not a limit"
            result.stdout shouldContain "petek capacity --measure"
            browser.sessions shouldHaveSize 0
            browser.stopCount shouldBe 0
        }

    @Test
    fun `--measure opens that many sessions on the target and reports measured figures`() =
        runBlocking<Unit> {
            assumeTrue(Files.isDirectory(Path.of("/proc/self")), "measuring reads /proc")
            val browser = FakeBrowserEngine()
            val cli = CliHarness(dir, mapOf("PETEK_TARGET" to "https://staging.portal.test"), browser = browser)

            val result = cli.run("capacity", "--measure", "3")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Measuring 3 real browser sessions on https://staging.portal.test"
            result.stdout shouldContain "(measured)"
            result.stdout shouldNotContain "(estimate)"
            browser.sessions shouldHaveSize 3
            browser.sessions.forEach { it.actions shouldContain "navigate https://staging.portal.test" }
            browser.sessions.forEach { it.closed shouldBe true }
            browser.stopCount shouldBe 1
        }

    @Test
    fun `--measure can open another page`() =
        runBlocking<Unit> {
            assumeTrue(Files.isDirectory(Path.of("/proc/self")), "measuring reads /proc")
            val browser = FakeBrowserEngine()
            val cli = CliHarness(dir, browser = browser)

            cli.run("capacity", "--measure", "1", "--url", "http://localhost:8080/login").statusCode shouldBe 0

            browser.sessions.single().actions shouldContain "navigate http://localhost:8080/login"
        }

    @Test
    fun `--measure refuses a production page before opening anything`() =
        runBlocking<Unit> {
            val browser = FakeBrowserEngine()
            val cli = CliHarness(dir, mapOf("PETEK_PRODUCTION_HOSTS" to "portal.example"), browser = browser)

            val result = cli.run("capacity", "--measure", "2", "--url", "https://portal.example/login")

            result.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
            result.stderr shouldContain "Refusing to contact the target"
            browser.sessions shouldHaveSize 0
        }

    @Test
    fun `--measure needs at least one session`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)

            // A usage error: Clikt reports it (PetekCli maps every usage error to exit code 2, see PetekCliTest).
            val result = cli.run("capacity", "--measure", "0")

            result.statusCode shouldNotBe 0
            result.stderr shouldContain "--measure"
        }
}
