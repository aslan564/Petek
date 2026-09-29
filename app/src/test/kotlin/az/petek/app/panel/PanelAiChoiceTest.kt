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

package az.petek.app.panel

import az.petek.app.config.ConfigLoader
import az.petek.app.config.EnvFile
import az.petek.app.config.IdentitySecretSource
import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import az.petek.dashboard.domain.AiChoice
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PanelUnavailableException
import az.petek.llm.domain.LlmProviderKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Faza 23: the AI chosen on the setup screen is kept in the configuration file and used without restarting the panel. */
class PanelAiChoiceTest {
    @TempDir
    lateinit var dir: Path

    private val env: Path get() = dir.resolve(".env")
    private var environment: Map<String, String> = emptyMap()
    private var busy = false

    private fun load(): PetekConfig = ConfigLoader(environment, dir, IdentitySecretSource { error("the file names its secret") }).load(env)

    private fun adapter(
        container: AppContainer,
        reload: (() -> PetekConfig)? = ::load,
    ) = PanelReadinessAdapter(container, env, reload) { busy }

    @BeforeEach
    fun file() {
        Files.writeString(
            env,
            "PETEK_TARGET=http://127.0.0.1:9\nPETEK_IDENTITY_SECRET=ai-choice-identity-secret-0123\nPETEK_EVIDENCE_DIR=evidence\n" +
                "PETEK_LLM_PROVIDER=none\n",
        )
    }

    @Test
    fun `the options name every AI the page offers, the one the file chose, and whether a key is set`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val options = adapter(container).aiOptions()

                options.options.map { it.provider } shouldContainExactly
                    listOf("auto", "codex-cli", "gemini-cli", "opencode-cli", "openai-compat", "anthropic-api", "none")
                options.options.single { it.provider == "openai-compat" }.needs shouldContainExactly listOf("model", "endpoint", "key")
                options.chosen shouldBe "none"
                options.keySet shouldBe false
                options.unavailable.shouldBeNull()
            }
        }

    @Test
    fun `a chosen AI is used without a restart and kept in the file, its key only there`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val choice = AiChoice("openai-compat", "model-1", "http://127.0.0.1:9/v1", "sk-local-key-0123456789")

                val view = adapter(container).chooseAi(choice)

                view.ai.provider shouldBe "openai-compat"
                view.ai.model shouldBe "model-1"
                container.config.llmProvider shouldBe LlmProviderKey.OPENAI_COMPAT
                container.llm.provider shouldBe LlmProviderKey.OPENAI_COMPAT
                val written = EnvFile.load(env)
                written["PETEK_LLM_PROVIDER"] shouldBe "openai-compat"
                written["PETEK_LLM_BASE_URL"] shouldBe "http://127.0.0.1:9/v1"
                written["PETEK_LLM_API_KEY"] shouldBe "sk-local-key-0123456789"
                choice.toString() shouldNotContain "sk-local"

                // A blank key keeps the one the file has.
                adapter(container).chooseAi(AiChoice("openai-compat", "model-2", "http://127.0.0.1:9/v1", ""))
                EnvFile.load(env)["PETEK_LLM_API_KEY"] shouldBe "sk-local-key-0123456789"
                adapter(container).aiOptions().keySet shouldBe true
            }
        }

    @Test
    fun `an incomplete choice is refused, naming the field, and nothing is written`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val before = Files.readString(env)

                val refused =
                    shouldThrow<PanelRequestException> { adapter(container).chooseAi(AiChoice("openai-compat", "model-1", "", null)) }

                refused.problems.map { it.field } shouldContainExactly listOf("endpoint")
                Files.readString(env) shouldBe before
                shouldThrow<PanelRequestException> { adapter(container).chooseAi(AiChoice("some-other-ai")) }
                    .problems
                    .single()
                    .field shouldBe "provider"
            }
        }

    @Test
    fun `a choice the environment would override is refused, and the file is put back`() =
        runBlocking<Unit> {
            environment = mapOf("PETEK_LLM_PROVIDER" to "none")
            AppContainer(load()).use { container ->
                val before = Files.readString(env)

                val refused = shouldThrow<PanelRequestException> { adapter(container).chooseAi(AiChoice("gemini-cli")) }

                refused.message.shouldNotBeNull() shouldContain "mühit dəyişəni"
                Files.readString(env) shouldBe before
                container.config.llmProvider shouldBe LlmProviderKey.NONE
            }
        }

    @Test
    fun `the AI is not switched while a test, an exploration or a run is going`() =
        runBlocking<Unit> {
            busy = true
            AppContainer(load()).use { container ->
                shouldThrow<PanelConflictException> { adapter(container).chooseAi(AiChoice("none")) }
            }
        }

    @Test
    fun `where the configuration cannot be read again the AI is only shown`() =
        runBlocking<Unit> {
            AppContainer(load()).use { container ->
                val shown = adapter(container, reload = null)

                shown.aiOptions().unavailable.shouldNotBeNull()
                shouldThrow<PanelUnavailableException> { shown.chooseAi(AiChoice("none")) }
            }
        }
}
