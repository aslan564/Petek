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

package az.petek.e2e

import az.petek.app.cli.CliRuntime
import az.petek.app.cli.PetekCommand
import az.petek.app.config.IdentitySecretSource
import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.FakeTargetServer
import com.github.ajalt.clikt.command.test
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The contract demo with a real AI (`./gradlew :e2e:liveTest`, the owner's own plan or quota): the repository's
 * `scenarios/contract-demo.yaml` with 6 testers, `petek run` against the in-process fake target in real Chromium, and
 * the testers' `do` steps decided by whatever AI the owner configured. Only the AI's settings are taken from the
 * owner (`PETEK_LLM_*` and the providers' key variables, from the environment or the repository's `.env`), never a
 * target: the site is always the fake target, and Pətək's home is a temporary one. The deterministic twin of this
 * run is `ContractDemoEndToEndTest` in the app module.
 */
@Tag("live")
class ContractDemoLiveTest {
    @TempDir
    lateinit var dir: Path

    private val target = FakeTargetServer(FakeTargetConfig()).start()

    @AfterEach
    fun stop() = target.close()

    @Test
    fun `the owner's AI takes the contract demo through to a passed run`() =
        runBlocking<Unit> {
            val ai = aiSettings()
            check(ai.keys.any { it.startsWith("PETEK_LLM_") || it in KEY_ALIASES }) {
                "liveTest needs your AI: set PETEK_LLM_PROVIDER (and its model or key) in the environment or in .env"
            }
            Files.writeString(dir.resolve(".env"), environment(ai))
            Files.createDirectories(dir.resolve("scenarios"))
            Files.copy(root().resolve("scenarios/contract-demo.yaml"), dir.resolve("scenarios/contract-demo.yaml"))
            val runtime =
                CliRuntime(
                    // PATH and HOME let an AI CLI start with its own sign-in; nothing else of the owner's shell is read.
                    environment = { System.getenv().filterKeys { it == "PATH" || it == "HOME" } },
                    workingDirectory = dir,
                    identitySecrets = IdentitySecretSource { error("the test's configuration names its secret") },
                    configureLogging = {},
                    observationWindow = Duration.ZERO,
                    openInBrowser = { false },
                    home = dir.resolve("petek-home"),
                )

            val result = PetekCommand(runtime).test(listOf("--json", "run", "scenarios/contract-demo.yaml", "--testers", "6"), width = WIDE)

            val out = Json.parseToJsonElement(result.stdout.substring(result.stdout.indexOf('{'))).jsonObject
            val run =
                out
                    .getValue("runs")
                    .jsonArray
                    .single()
                    .jsonObject
            withClue("report: ${run["report"]?.jsonPrimitive?.content}") {
                run.getValue("outcome").jsonPrimitive.content shouldBe "PASSED"
                out.getValue("exitCode").jsonPrimitive.int shouldBe 0
            }
            target.store.companies.size shouldBe 0
        }

    /** The fake target's settings plus the owner's AI settings; the AI's lines win nothing else. */
    private fun environment(ai: Map<String, String>): String =
        (
            listOf(
                "PETEK_TARGET=${target.baseUrl}",
                "PETEK_PRODUCTION_HOSTS=",
                "PETEK_TEST_TOKEN=dev-token",
                "PETEK_MAILPIT_URL=${target.mailpitUrl}",
                "PETEK_MAIL_DOMAIN=test.portal.example",
                "PETEK_IDENTITY_SECRET=contract-demo-live-secret",
                "PETEK_BROWSER_HEADLESS=true",
                "PETEK_EVIDENCE_DIR=evidence",
            ) + ai.map { (key, value) -> "$key=$value" }
        ).joinToString("\n") + "\n"

    /** `PETEK_LLM_*` and the providers' key variables: the environment first, then the repository's `.env`. */
    private fun aiSettings(): Map<String, String> {
        val file = root().resolve(".env")
        val fromFile =
            if (!Files.isRegularFile(file)) {
                emptyMap()
            } else {
                Files
                    .readAllLines(file)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            }
        return (fromFile + System.getenv()).filterKeys { it.startsWith("PETEK_LLM_") || it in KEY_ALIASES }.filterValues { it.isNotBlank() }
    }

    /** The repository root: the e2e tests run there. */
    private fun root(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private companion object {
        const val WIDE = 250
        val KEY_ALIASES = setOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "XAI_API_KEY", "OPENROUTER_API_KEY", "GEMINI_API_KEY")
    }
}
