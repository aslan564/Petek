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

import az.petek.app.config.EnvFile
import az.petek.app.testing.CliHarness
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class PetekCliTest {
    @TempDir
    lateinit var dir: Path

    private val cli: PetekCli get() = PetekCli(CliHarness(dir).runtime)

    @Test
    fun `a successful command line returns 0 instead of exiting`() =
        runBlocking<Unit> {
            cli.execute(listOf("--help")) shouldBe ExitCodes.OK
        }

    @Test
    fun `a wrong command line exits with 2, never with the 1 of a failed run`() =
        runBlocking<Unit> {
            cli.execute(listOf("--verbose")) shouldBe ExitCodes.CONFIG_OR_ABORTED
            cli.execute(listOf("no-such-command")) shouldBe ExitCodes.CONFIG_OR_ABORTED
            cli.execute(listOf("run")) shouldBe ExitCodes.CONFIG_OR_ABORTED
            cli.execute(listOf("run", "tiny.yaml", "--testers", "0")) shouldBe ExitCodes.CONFIG_OR_ABORTED
            cli.execute(listOf("plan", "tiny.yaml", "--no-such-option")) shouldBe ExitCodes.CONFIG_OR_ABORTED
        }

    @Test
    fun `no command at all opens the web panel`() {
        PetekCli.effectiveArguments(emptyList()) shouldBe listOf(PanelCommand.NAME)
        PetekCli.effectiveArguments(listOf("doctor")) shouldBe listOf("doctor")
    }

    @Test
    fun `the panel without a site to test asks for it in the browser, then writes env and opens for the answer`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir).apply { env.remove("PETEK_TARGET") }
            val command = launch(Dispatchers.Default) { cli.run("panel", "--port", "0") }
            try {
                val question = withTimeout(30.seconds) { awaitOpened(cli) }
                Files.exists(dir.resolve(".env")) shouldBe false
                Files.exists(cli.evidenceDir) shouldBe false

                val page = http.send(get(question), BodyHandlers.ofString()).body()
                val token = TOKEN.find(page).shouldNotBeNull().groupValues[1]
                val answer = http.send(setup(question, token, "http://127.0.0.1:9"), BodyHandlers.ofString())

                answer.statusCode() shouldBe 200
                val panel =
                    Json
                        .parseToJsonElement(answer.body())
                        .jsonObject["panel"]
                        .shouldNotBeNull()
                        .jsonPrimitive.content
                EnvFile.load(dir.resolve(".env"))["PETEK_TARGET"] shouldBe "http://127.0.0.1:9"
                http.send(get(panel), BodyHandlers.ofString()).statusCode() shouldBe 200
                http
                    .send(get(question), BodyHandlers.discarding())
                    .headers()
                    .firstValue("Location")
                    .orElse(null) shouldBe panel
            } finally {
                command.cancelAndJoin()
            }
        }

    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun get(url: String): HttpRequest = HttpRequest.newBuilder(URI(url)).GET().build()

    private fun setup(
        question: String,
        token: String,
        target: String,
    ): HttpRequest =
        HttpRequest
            .newBuilder(URI(question).resolve("/api/setup"))
            .header("Content-Type", "application/json")
            .header("X-Petek-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString("""{"target":"$target"}"""))
            .build()

    private suspend fun awaitOpened(cli: CliHarness): String {
        while (cli.opened.isEmpty()) delay(20)
        return cli.opened.first()
    }

    @Test
    fun `help of a command exits with 0`() =
        runBlocking<Unit> {
            cli.execute(listOf("run", "--help")) shouldBe ExitCodes.OK
        }

    @Test
    fun `a failing command returns its exit code`() =
        runBlocking<Unit> {
            cli.execute(listOf("report", "run_unknown")) shouldBe ExitCodes.FAILURE
            cli.execute(listOf("--env-file", "absent.env", "doctor")) shouldBe ExitCodes.CONFIG_OR_ABORTED
        }

    private companion object {
        val TOKEN = Regex("""<meta name="petek-token" content="([A-Za-z0-9_-]+)">""")
    }
}
